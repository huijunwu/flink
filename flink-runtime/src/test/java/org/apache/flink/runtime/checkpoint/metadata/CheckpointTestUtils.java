/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint.metadata;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.checkpoint.MasterState;
import org.apache.flink.runtime.checkpoint.OperatorState;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.StateObjectCollection;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.IncrementalRemoteKeyedStateHandle;
import org.apache.flink.runtime.state.InputChannelStateHandle;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupRangeOffsets;
import org.apache.flink.runtime.state.KeyGroupsStateHandle;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.runtime.state.OperatorStreamStateHandle;
import org.apache.flink.runtime.state.ResultSubpartitionStateHandle;
import org.apache.flink.runtime.state.StateHandleID;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.runtime.state.filesystem.RelativeFileStateHandle;
import org.apache.flink.runtime.state.memory.ByteStreamStateHandle;
import org.apache.flink.util.StringUtils;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.apache.flink.runtime.checkpoint.StateHandleDummyUtil.createNewInputChannelStateHandle;
import static org.apache.flink.runtime.checkpoint.StateHandleDummyUtil.createNewResultSubpartitionStateHandle;
import static org.apache.flink.runtime.checkpoint.StateObjectCollection.empty;
import static org.apache.flink.runtime.checkpoint.StateObjectCollection.singleton;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * A collection of utility methods for testing the (de)serialization of
 * checkpoint metadata for persistence.
 */
public class CheckpointTestUtils {

	/**
	 * Creates a random collection of OperatorState objects containing various types of state handles.
	 *
	 * @param basePath The basePath for savepoint, will be null for checkpoint.
	 * @param numTaskStates Number of tasks.
	 * @param numSubtasksPerTask Number of subtask for each task.
	 */
	public static Collection<OperatorState> createOperatorStates(
			Random random,
			@Nullable String basePath,
			int numTaskStates,
			int numSubtasksPerTask) {

		List<OperatorState> taskStates = new ArrayList<>(numTaskStates);

		for (int stateIdx = 0; stateIdx < numTaskStates; ++stateIdx) {

			OperatorState taskState = new OperatorState(new OperatorID(), numSubtasksPerTask, 128);

			final boolean hasCoordinatorState = random.nextBoolean();
			if (hasCoordinatorState) {
				final ByteStreamStateHandle stateHandle = createDummyByteStreamStreamStateHandle(random);
				taskState.setCoordinatorState(stateHandle);
			}

			boolean hasOperatorStateBackend = random.nextBoolean();
			boolean hasOperatorStateStream = random.nextBoolean();

			boolean hasKeyedBackend = random.nextInt(4) != 0;
			boolean hasKeyedStream = random.nextInt(4) != 0;
			boolean isIncremental = random.nextInt(3) == 0;

			for (int subtaskIdx = 0; subtaskIdx < numSubtasksPerTask; subtaskIdx++) {

				StreamStateHandle operatorStateBackend =
					new ByteStreamStateHandle("b", ("Beautiful").getBytes(ConfigConstants.DEFAULT_CHARSET));
				StreamStateHandle operatorStateStream =
					new ByteStreamStateHandle("b", ("Beautiful").getBytes(ConfigConstants.DEFAULT_CHARSET));

				Map<String, OperatorStateHandle.StateMetaInfo> offsetsMap = new HashMap<>();
				offsetsMap.put("A", new OperatorStateHandle.StateMetaInfo(new long[]{0, 10, 20}, OperatorStateHandle.Mode.SPLIT_DISTRIBUTE));
				offsetsMap.put("B", new OperatorStateHandle.StateMetaInfo(new long[]{30, 40, 50}, OperatorStateHandle.Mode.SPLIT_DISTRIBUTE));
				offsetsMap.put("C", new OperatorStateHandle.StateMetaInfo(new long[]{60, 70, 80}, OperatorStateHandle.Mode.UNION));

				final OperatorSubtaskState.Builder state = OperatorSubtaskState.builder();
				if (hasOperatorStateBackend) {
					state.setManagedOperatorState(new OperatorStreamStateHandle(offsetsMap, operatorStateBackend));
				}

				if (hasOperatorStateStream) {
					state.setRawOperatorState(new OperatorStreamStateHandle(offsetsMap, operatorStateStream));
				}

				if (hasKeyedBackend) {
					state.setRawKeyedState(isIncremental && !isSavepoint(basePath) ?
						createDummyIncrementalKeyedStateHandle(random) :
						createDummyKeyGroupStateHandle(random, basePath));
				}

				if (hasKeyedStream) {
					state.setManagedKeyedState(createDummyKeyGroupStateHandle(random, basePath));
				}

				state.setInputChannelState((random.nextBoolean() && !isSavepoint(basePath)) ?
					singleton(createNewInputChannelStateHandle(random.nextInt(5), random)) :
					empty());
				state.setResultSubpartitionState((random.nextBoolean() && !isSavepoint(basePath)) ?
					singleton(createNewResultSubpartitionStateHandle(random.nextInt(5), random)) :
					empty());

				taskState.putState(subtaskIdx, state.build());
			}

			taskStates.add(taskState);
		}

		return taskStates;
	}

	private static boolean isSavepoint(String basePath) {
		return basePath != null;
	}

	/**
	 * Creates a bunch of random master states.
	 */
	public static Collection<MasterState> createRandomMasterStates(Random random, int num) {
		final ArrayList<MasterState> states = new ArrayList<>(num);

		for (int i = 0; i < num; i++) {
			int version = random.nextInt(10);
			String name = StringUtils.getRandomString(random, 5, 500);
			byte[] bytes = new byte[random.nextInt(5000) + 1];
			random.nextBytes(bytes);

			states.add(new MasterState(name, bytes, version));
		}

		return states;
	}

	/**
	 * Asserts that two MasterStates are equal.
	 *
	 * <p>The MasterState avoids overriding {@code equals()} on purpose, because equality is not well
	 * defined in the raw contents.
	 */
	public static void assertMasterStateEquality(MasterState a, MasterState b) {
		assertEquals(a.version(), b.version());
		assertEquals(a.name(), b.name());
		assertArrayEquals(a.bytes(), b.bytes());

	}

	// ------------------------------------------------------------------------

	/** utility class, not meant to be instantiated. */
	private CheckpointTestUtils() {}

	public static IncrementalRemoteKeyedStateHandle createDummyIncrementalKeyedStateHandle(Random rnd) {
		return new IncrementalRemoteKeyedStateHandle(
			createRandomUUID(rnd),
			new KeyGroupRange(1, 1),
			42L,
			createRandomStateHandleMap(rnd),
			createRandomStateHandleMap(rnd),
			createDummyStreamStateHandle(rnd, null));
	}

	public static Map<StateHandleID, StreamStateHandle> createRandomStateHandleMap(Random rnd) {
		final int size = rnd.nextInt(4);
		Map<StateHandleID, StreamStateHandle> result = new HashMap<>(size);
		for (int i = 0; i < size; ++i) {
			StateHandleID randomId = new StateHandleID(createRandomUUID(rnd).toString());
			StreamStateHandle stateHandle = createDummyStreamStateHandle(rnd, null);
			result.put(randomId, stateHandle);
		}

		return result;
	}

	public static KeyGroupsStateHandle createDummyKeyGroupStateHandle(Random rnd, String basePath) {
		return new KeyGroupsStateHandle(
			new KeyGroupRangeOffsets(1, 1, new long[]{rnd.nextInt(1024)}),
			createDummyStreamStateHandle(rnd, basePath));
	}

	public static ByteStreamStateHandle createDummyByteStreamStreamStateHandle(Random rnd) {
		return (ByteStreamStateHandle) createDummyStreamStateHandle(rnd, null);
	}

	public static StreamStateHandle createDummyStreamStateHandle(Random rnd, @Nullable String basePath) {
		if (!isSavepoint(basePath)) {
			return new ByteStreamStateHandle(
				String.valueOf(createRandomUUID(rnd)),
				String.valueOf(createRandomUUID(rnd)).getBytes(ConfigConstants.DEFAULT_CHARSET));
		} else {
			long stateSize = rnd.nextLong();
			if (stateSize <= 0) {
				stateSize = -stateSize;
			}
			String relativePath = String.valueOf(createRandomUUID(rnd));
			Path statePath = new Path(basePath, relativePath);
			return new RelativeFileStateHandle(statePath, relativePath, stateSize);
		}
	}

	private static UUID createRandomUUID(Random rnd) {
		return new UUID(rnd.nextLong(), rnd.nextLong());
	}

}
