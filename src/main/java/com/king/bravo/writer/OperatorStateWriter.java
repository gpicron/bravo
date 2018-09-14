package com.king.bravo.writer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.flink.api.common.operators.Order;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.memory.ByteArrayDataOutputView;
import org.apache.flink.runtime.checkpoint.OperatorState;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.StateObjectCollection;
import org.apache.flink.runtime.checkpoint.savepoint.Savepoint;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.CheckpointedStateScope;
import org.apache.flink.runtime.state.KeyedBackendSerializationProxy;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.runtime.state.RegisteredKeyValueStateBackendMetaInfo;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.runtime.state.filesystem.FileBasedStateOutputStream;
import org.apache.flink.runtime.state.metainfo.StateMetaInfoSnapshot;

import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import com.king.bravo.reader.OperatorStateReader;
import com.king.bravo.types.KeyedStateRow;
import com.king.bravo.utils.StateMetadataUtils;
import com.king.bravo.writer.functions.KeyGroupAndStateNameKey;
import com.king.bravo.writer.functions.OperatorIndexForKeyGroupKey;
import com.king.bravo.writer.functions.RocksDBSavepointWriter;
import com.king.bravo.writer.functions.ValueStateToKeyedStateRow;

public class OperatorStateWriter {

	private OperatorState baseOpState;
	private DataSet<KeyedStateRow> allRows = null;
	private Path newCheckpointBasePath;
	private long checkpointId;
	private BiConsumer<Integer, OperatorStateBackend> transformer;
	private Map<String, StateMetaInfoSnapshot> metaSnapshots;
	private KeyedBackendSerializationProxy<?> proxy;

	public OperatorStateWriter(Savepoint sp, String uid, Path newCheckpointBasePath) {
		this(sp.getCheckpointId(), StateMetadataUtils.getOperatorState(sp, uid), newCheckpointBasePath);
	}

	public OperatorStateWriter(long checkpointId, OperatorState baseOpState, Path newCheckpointBasePath) {
		this.baseOpState = baseOpState;
		this.newCheckpointBasePath = newCheckpointBasePath;
		this.checkpointId = checkpointId;

		proxy = StateMetadataUtils.getKeyedBackendSerializationProxy(baseOpState);
		metaSnapshots = new HashMap<>();
		proxy.getStateMetaInfoSnapshots().forEach(ms -> metaSnapshots.put(ms.getName(), ms));
		proxy = StateMetadataUtils.getKeyedBackendSerializationProxy(baseOpState);
	}

	public void addKeyedStateRows(DataSet<KeyedStateRow> rows) {
		allRows = allRows == null ? rows : allRows.union(rows);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public <K, V> void addValueState(String stateName, DataSet<Tuple2<K, V>> newState) {
		addKeyedStateRows(newState
				.map(new ValueStateToKeyedStateRow<K, V>(stateName,
						(TypeSerializer<K>) (TypeSerializer) proxy.getKeySerializer(),
						(TypeSerializer<V>) (TypeSerializer) StateMetadataUtils.getSerializer(proxy, stateName)
								.orElseThrow(
										() -> new IllegalArgumentException("Cannot find state " + stateName)),
						baseOpState.getMaxParallelism())));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public <K, V> void createNewValueState(String stateName, DataSet<Tuple2<K, V>> newState,
			TypeSerializer<V> valueSerializer) {

		metaSnapshots.put(stateName, new RegisteredKeyValueStateBackendMetaInfo<>(StateDescriptor.Type.VALUE, stateName,
				VoidNamespaceSerializer.INSTANCE, valueSerializer).snapshot());

		proxy = new KeyedBackendSerializationProxy<>(proxy.getKeySerializer(), new ArrayList<>(metaSnapshots.values()),
				proxy.isUsingKeyGroupCompression());

		addKeyedStateRows(newState
				.map(new ValueStateToKeyedStateRow<K, V>(stateName,
						(TypeSerializer<K>) (TypeSerializer) proxy.getKeySerializer(),
						valueSerializer,
						baseOpState.getMaxParallelism())));
	}

	public OperatorState writeAll() throws Exception {
		int maxParallelism = baseOpState.getMaxParallelism();
		int parallelism = baseOpState.getParallelism();
		Path outDir = makeOutputDir();

		ByteArrayDataOutputView bow = new ByteArrayDataOutputView();
		proxy.write(bow);

		DataSet<Tuple2<Integer, KeyedStateHandle>> handles = allRows
				.groupBy(new OperatorIndexForKeyGroupKey(maxParallelism, parallelism))
				.sortGroup(new KeyGroupAndStateNameKey(maxParallelism), Order.ASCENDING)
				.reduceGroup(new RocksDBSavepointWriter(maxParallelism, parallelism,
						HashBiMap.create(StateMetadataUtils.getStateIdMapping(proxy)).inverse(),
						proxy.isUsingKeyGroupCompression(), outDir, bow.toByteArray()));

		Map<Integer, KeyedStateHandle> handleMap = handles.collect().stream()
				.collect(Collectors.toMap(t -> t.f0, t -> t.f1));

		// We construct a new operatorstate with the collected handles
		OperatorState newOperatorState = new OperatorState(baseOpState.getOperatorID(), parallelism, maxParallelism);

		// Fill with the subtaskstates based on the old one (we need to preserve the
		// other states)
		baseOpState.getSubtaskStates().forEach((subtaskId, subtaskState) -> {
			KeyedStateHandle newKeyedHandle = handleMap.get(subtaskId);
			StateObjectCollection<OperatorStateHandle> opHandle = transformSubtaskOpState(outDir, subtaskId,
					subtaskState.getManagedOperatorState());

			newOperatorState.putState(subtaskId,
					new OperatorSubtaskState(
							opHandle,
							subtaskState.getRawOperatorState(),
							new StateObjectCollection<>(
									newKeyedHandle != null
											? Lists.newArrayList(newKeyedHandle)
											: Collections.emptyList()),
							subtaskState.getRawKeyedState()));
		});

		return newOperatorState;
	}

	private StateObjectCollection<OperatorStateHandle> transformSubtaskOpState(Path outDir, Integer subtaskId,
			StateObjectCollection<OperatorStateHandle> baseState) {

		if (transformer == null) {
			return baseState;
		}

		StateObjectCollection<OperatorStateHandle> opHandle = baseState;
		try (OperatorStateBackend opBackend = OperatorStateReader
				.restoreOperatorStateBackend(opHandle)) {

			transformer.accept(subtaskId, opBackend);

			OperatorStateHandle newSnapshot = opBackend
					.snapshot(checkpointId, System.currentTimeMillis(), new CheckpointStreamFactory() {
						@Override
						public CheckpointStateOutputStream createCheckpointStateOutputStream(
								CheckpointedStateScope scope)
								throws IOException {
							return new FileBasedStateOutputStream(outDir.getFileSystem(),
									new Path(outDir, String.valueOf(UUID.randomUUID())));
						}
					}, null).get().getJobManagerOwnedSnapshot();
			return new StateObjectCollection<>(Lists.newArrayList(newSnapshot));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public <K, V> void transformNonKeyedState(BiConsumer<Integer, OperatorStateBackend> transformer) throws Exception {
		this.transformer = transformer;
	}

	private Path makeOutputDir() {
		final Path outDir = new Path(new Path(newCheckpointBasePath, "mchk-" + checkpointId),
				"op-" + baseOpState.getOperatorID());
		try {
			outDir.getFileSystem().mkdirs(outDir);
		} catch (IOException ignore) {}
		return outDir;
	}

}
