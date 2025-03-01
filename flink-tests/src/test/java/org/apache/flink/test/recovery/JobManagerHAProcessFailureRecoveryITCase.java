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

package org.apache.flink.test.recovery;

import org.apache.flink.api.common.ExecutionMode;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Deadline;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.io.DiscardingOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.NettyShuffleEnvironmentOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.plugin.PluginManager;
import org.apache.flink.core.plugin.PluginUtils;
import org.apache.flink.core.testutils.EachCallbackWrapper;
import org.apache.flink.runtime.dispatcher.DispatcherGateway;
import org.apache.flink.runtime.dispatcher.DispatcherId;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServicesUtils;
import org.apache.flink.runtime.leaderelection.TestingListener;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.runtime.rest.util.NoOpFatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.RpcSystem;
import org.apache.flink.runtime.rpc.RpcUtils;
import org.apache.flink.runtime.taskexecutor.TaskExecutorResourceUtils;
import org.apache.flink.runtime.taskexecutor.TaskManagerRunner;
import org.apache.flink.runtime.testutils.DispatcherProcess;
import org.apache.flink.runtime.testutils.ZooKeeperTestUtils;
import org.apache.flink.runtime.zookeeper.ZooKeeperExtension;
import org.apache.flink.testutils.TestingUtils;
import org.apache.flink.testutils.executor.TestExecutorExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameter;
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameters;
import org.apache.flink.testutils.junit.utils.TempDirUtils;
import org.apache.flink.util.Collector;
import org.apache.flink.util.concurrent.FutureUtils;
import org.apache.flink.util.concurrent.ScheduledExecutorServiceAdapter;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verify behaviour in case of JobManager process failure during job execution.
 *
 * <p>The test works with multiple job managers processes by spawning JVMs.
 *
 * <p>Initially, it starts two TaskManager (2 slots each) and two JobManager JVMs.
 *
 * <p>It submits a program with parallelism 4 and waits until all tasks are brought up. Coordination
 * between the test and the tasks happens via checking for the existence of temporary files. It then
 * kills the leading JobManager process. The recovery should restart the tasks on the new
 * JobManager.
 *
 * <p>This follows the same structure as {@link AbstractTaskManagerProcessFailureRecoveryTest}.
 */
@SuppressWarnings("serial")
@ExtendWith(ParameterizedTestExtension.class)
class JobManagerHAProcessFailureRecoveryITCase {

    private final ZooKeeperExtension zooKeeperExtension = new ZooKeeperExtension();

    @RegisterExtension
    final EachCallbackWrapper<ZooKeeperExtension> zooKeeperResource =
            new EachCallbackWrapper<>(zooKeeperExtension);

    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(5);

    @RegisterExtension
    static final TestExecutorExtension<ScheduledExecutorService> EXECUTOR_EXTENSION =
            TestingUtils.defaultExecutorExtension();

    @TempDir private java.nio.file.Path tempDir;

    protected static final String READY_MARKER_FILE_PREFIX = "ready_";
    protected static final String FINISH_MARKER_FILE_PREFIX = "finish_";
    protected static final String PROCEED_MARKER_FILE = "proceed";

    protected static final int PARALLELISM = 4;

    // --------------------------------------------------------------------------------------------
    //  Parametrization (run pipelined and batch)
    // --------------------------------------------------------------------------------------------

    @Parameter private ExecutionMode executionMode;

    @Parameters(name = "executionMode={0}")
    private static Collection<ExecutionMode> executionMode() {
        return Arrays.asList(ExecutionMode.PIPELINED, ExecutionMode.BATCH);
    }

    /**
     * Test program with JobManager failure.
     *
     * @param zkQuorum ZooKeeper quorum to connect to
     * @param coordinateDir Coordination directory
     * @throws Exception
     */
    private void testJobManagerFailure(
            String zkQuorum, final File coordinateDir, final File zookeeperStoragePath)
            throws Exception {
        Configuration config = new Configuration();
        config.setString(HighAvailabilityOptions.HA_MODE, "ZOOKEEPER");
        config.setString(HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, zkQuorum);
        config.setString(
                HighAvailabilityOptions.HA_STORAGE_PATH, zookeeperStoragePath.getAbsolutePath());

        ExecutionEnvironment env =
                ExecutionEnvironment.createRemoteEnvironment("leader", 1, config);
        env.setParallelism(PARALLELISM);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, 0L));
        env.getConfig().setExecutionMode(executionMode);

        final long numElements = 100000L;
        final DataSet<Long> result =
                env.generateSequence(1, numElements)
                        // make sure every mapper is involved (no one is skipped because of lazy
                        // split assignment)
                        .rebalance()
                        // the majority of the behavior is in the MapFunction
                        .map(
                                new RichMapFunction<Long, Long>() {

                                    private final File proceedFile =
                                            new File(coordinateDir, PROCEED_MARKER_FILE);

                                    private boolean markerCreated = false;
                                    private boolean checkForProceedFile = true;

                                    @Override
                                    public Long map(Long value) throws Exception {
                                        if (!markerCreated) {
                                            int taskIndex =
                                                    getRuntimeContext().getIndexOfThisSubtask();
                                            AbstractTaskManagerProcessFailureRecoveryTest.touchFile(
                                                    new File(
                                                            coordinateDir,
                                                            READY_MARKER_FILE_PREFIX + taskIndex));
                                            markerCreated = true;
                                        }

                                        // check if the proceed file exists
                                        if (checkForProceedFile) {
                                            if (proceedFile.exists()) {
                                                checkForProceedFile = false;
                                            } else {
                                                // otherwise wait so that we make slow progress
                                                Thread.sleep(100);
                                            }
                                        }
                                        return value;
                                    }
                                })
                        .reduce(
                                new ReduceFunction<Long>() {
                                    @Override
                                    public Long reduce(Long value1, Long value2) {
                                        return value1 + value2;
                                    }
                                })
                        // The check is done in the mapper, because the client can currently not
                        // handle
                        // job manager losses/reconnects.
                        .flatMap(
                                new RichFlatMapFunction<Long, Long>() {
                                    @Override
                                    public void flatMap(Long value, Collector<Long> out)
                                            throws Exception {
                                        assertThat((long) value)
                                                .isEqualTo(numElements * (numElements + 1L) / 2L);

                                        int taskIndex = getRuntimeContext().getIndexOfThisSubtask();
                                        AbstractTaskManagerProcessFailureRecoveryTest.touchFile(
                                                new File(
                                                        coordinateDir,
                                                        FINISH_MARKER_FILE_PREFIX + taskIndex));
                                    }
                                });

        result.output(new DiscardingOutputFormat<>());

        env.execute();
    }

    @TestTemplate
    void testDispatcherProcessFailure() throws Exception {
        final File zookeeperStoragePath = TempDirUtils.newFolder(tempDir);

        // Config
        final int numberOfJobManagers = 2;
        final int numberOfTaskManagers = 2;
        final int numberOfSlotsPerTaskManager = 2;

        assertThat(numberOfTaskManagers * numberOfSlotsPerTaskManager).isEqualTo(PARALLELISM);

        // Job managers
        final DispatcherProcess[] dispatcherProcesses = new DispatcherProcess[numberOfJobManagers];

        // Task managers
        TaskManagerRunner[] taskManagerRunners = new TaskManagerRunner[numberOfTaskManagers];

        HighAvailabilityServices highAvailabilityServices = null;

        LeaderRetrievalService leaderRetrievalService = null;

        // Coordination between the processes goes through a directory
        File coordinateTempDir = null;

        // Cluster config
        Configuration config =
                ZooKeeperTestUtils.createZooKeeperHAConfig(
                        zooKeeperExtension.getConnectString(), zookeeperStoragePath.getPath());
        // Task manager configuration
        config.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.parse("4m"));
        config.set(TaskManagerOptions.NETWORK_MEMORY_MIN, MemorySize.parse("3200k"));
        config.set(TaskManagerOptions.NETWORK_MEMORY_MAX, MemorySize.parse("3200k"));
        config.set(NettyShuffleEnvironmentOptions.NETWORK_SORT_SHUFFLE_MIN_BUFFERS, 16);
        config.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, 2);
        config.set(TaskManagerOptions.TASK_HEAP_MEMORY, MemorySize.parse("128m"));
        config.set(TaskManagerOptions.CPU_CORES, 1.0);
        TaskExecutorResourceUtils.adjustForLocalExecution(config);

        final RpcService rpcService =
                RpcSystem.load().remoteServiceBuilder(config, "localhost", "0").createAndStart();

        try {
            final Deadline deadline = Deadline.fromNow(TEST_TIMEOUT);

            // Coordination directory
            coordinateTempDir = TempDirUtils.newFolder(tempDir);

            // Start first process
            dispatcherProcesses[0] = new DispatcherProcess(0, config);
            dispatcherProcesses[0].startProcess();

            highAvailabilityServices =
                    HighAvailabilityServicesUtils.createAvailableOrEmbeddedServices(
                            config,
                            EXECUTOR_EXTENSION.getExecutor(),
                            NoOpFatalErrorHandler.INSTANCE);

            final PluginManager pluginManager =
                    PluginUtils.createPluginManagerFromRootFolder(config);
            // Start the task manager process
            for (int i = 0; i < numberOfTaskManagers; i++) {
                taskManagerRunners[i] =
                        new TaskManagerRunner(
                                config,
                                pluginManager,
                                TaskManagerRunner::createTaskExecutorService);
                taskManagerRunners[i].start();
            }

            // Leader listener
            TestingListener leaderListener = new TestingListener();
            leaderRetrievalService = highAvailabilityServices.getDispatcherLeaderRetriever();
            leaderRetrievalService.start(leaderListener);

            // Initial submission
            leaderListener.waitForNewLeader();

            String leaderAddress = leaderListener.getAddress();
            UUID leaderId = leaderListener.getLeaderSessionID();

            final CompletableFuture<DispatcherGateway> dispatcherGatewayFuture =
                    rpcService.connect(
                            leaderAddress,
                            DispatcherId.fromUuid(leaderId),
                            DispatcherGateway.class);
            final DispatcherGateway dispatcherGateway = dispatcherGatewayFuture.get();

            // Wait for all task managers to connect to the leading job manager
            waitForTaskManagers(numberOfTaskManagers, dispatcherGateway, deadline.timeLeft());

            final File coordinateDirClosure = coordinateTempDir;
            final Throwable[] errorRef = new Throwable[1];

            // we trigger program execution in a separate thread
            Thread programTrigger =
                    new Thread("Program Trigger") {
                        @Override
                        public void run() {
                            try {
                                testJobManagerFailure(
                                        zooKeeperExtension.getConnectString(),
                                        coordinateDirClosure,
                                        zookeeperStoragePath);
                            } catch (Throwable t) {
                                t.printStackTrace();
                                errorRef[0] = t;
                            }
                        }
                    };

            // start the test program
            programTrigger.start();

            // wait until all marker files are in place, indicating that all tasks have started
            AbstractTaskManagerProcessFailureRecoveryTest.waitForMarkerFiles(
                    coordinateTempDir,
                    READY_MARKER_FILE_PREFIX,
                    PARALLELISM,
                    deadline.timeLeft().toMillis());

            // Kill one of the job managers and trigger recovery
            dispatcherProcesses[0].destroy();

            dispatcherProcesses[1] = new DispatcherProcess(1, config);
            dispatcherProcesses[1].startProcess();

            // we create the marker file which signals the program functions tasks that they can
            // complete
            AbstractTaskManagerProcessFailureRecoveryTest.touchFile(
                    new File(coordinateTempDir, PROCEED_MARKER_FILE));

            programTrigger.join(deadline.timeLeft().toMillis());

            // We wait for the finish marker file. We don't wait for the program trigger, because
            // we submit in detached mode.
            AbstractTaskManagerProcessFailureRecoveryTest.waitForMarkerFiles(
                    coordinateTempDir,
                    FINISH_MARKER_FILE_PREFIX,
                    1,
                    deadline.timeLeft().toMillis());

            // check that the program really finished
            assertThat(programTrigger.isAlive()).as("The program did not finish in time").isFalse();

            // check whether the program encountered an error
            if (errorRef[0] != null) {
                Throwable error = errorRef[0];
                error.printStackTrace();
                fail(
                        "The program encountered a "
                                + error.getClass().getSimpleName()
                                + " : "
                                + error.getMessage());
            }
        } catch (Throwable t) {
            // Print early (in some situations the process logs get too big
            // for Travis and the root problem is not shown)
            t.printStackTrace();

            for (DispatcherProcess p : dispatcherProcesses) {
                if (p != null) {
                    p.printProcessLog();
                }
            }

            throw t;
        } finally {
            for (int i = 0; i < numberOfTaskManagers; i++) {
                if (taskManagerRunners[i] != null) {
                    taskManagerRunners[i].close();
                }
            }

            if (leaderRetrievalService != null) {
                leaderRetrievalService.stop();
            }

            for (DispatcherProcess dispatcherProcess : dispatcherProcesses) {
                if (dispatcherProcess != null) {
                    dispatcherProcess.destroy();
                }
            }

            if (highAvailabilityServices != null) {
                highAvailabilityServices.closeAndCleanupAllData();
            }

            RpcUtils.terminateRpcService(rpcService);

            // Delete coordination directory
            if (coordinateTempDir != null) {
                try {
                    FileUtils.deleteDirectory(coordinateTempDir);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void waitForTaskManagers(
            int numberOfTaskManagers, DispatcherGateway dispatcherGateway, Duration timeLeft)
            throws ExecutionException, InterruptedException {
        FutureUtils.retrySuccessfulWithDelay(
                        () ->
                                dispatcherGateway.requestClusterOverview(
                                        Time.milliseconds(timeLeft.toMillis())),
                        Duration.ofMillis(50L),
                        org.apache.flink.api.common.time.Deadline.fromNow(
                                Duration.ofMillis(timeLeft.toMillis())),
                        clusterOverview ->
                                clusterOverview.getNumTaskManagersConnected()
                                        >= numberOfTaskManagers,
                        new ScheduledExecutorServiceAdapter(
                                Executors.newSingleThreadScheduledExecutor()))
                .get();
    }
}
