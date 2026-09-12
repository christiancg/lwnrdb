package org.techhouse;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.TransactionOperationHelper;

/**
 * The step order is the contract: refuse new work before draining so the queues can reach empty, release open
 * transactions before the drains so no queued event waits on a lock nobody will free, and leave the cluster last.
 */
public class ShutdownCoordinator {
    private final Logger logger = Logger.logFor(ShutdownCoordinator.class);
    private final Configuration configuration = Configuration.getInstance();
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final ScriptRunHistory scriptRunHistory = IocContainer.get(ScriptRunHistory.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final Tx2pcRecovery tx2pcRecovery = IocContainer.get(Tx2pcRecovery.class);

    private volatile boolean alreadyRun;

    public void shutdown(SocketServer socketServer, ClusterServer clusterServer) {
        if (alreadyRun) {
            return;
        }
        alreadyRun = true;
        final var budget = configuration.getShutdownTimeoutMs();
        final var deadline = System.currentTimeMillis() + budget;
        logger.info("Shutting down; up to " + budget + "ms to finish outstanding work");

        step("stop accepting connections", () -> {
            if (socketServer != null) {
                socketServer.stopAccepting();
            }
        });
        step("stop background sweeps", () -> {
            memoryManagement.stopSweepThread();
            scriptRunHistory.stopSweep();
            if (clusterConfig.isEnabled()) {
                antiEntropyService.stop();
                adminAntiEntropyService.stop();
                tx2pcRecovery.stop();
            }
        });
        step("roll back open transactions", TransactionOperationHelper::rollbackOpenTransactionsAtShutdown);
        step("drain triggers", () -> triggerExecutor.drain(remaining(deadline)));
        // After the triggers and before the background queue: a scheduled run enqueues into both.
        step("drain schedules", () -> scheduleExecutor.drain(remaining(deadline)));
        step("drain background tasks", () -> backgroundTaskManager.drain(remaining(deadline)));
        step("stop listen workers", listenManager::stopWorkers);
        step("leave the cluster", () -> {
            if (clusterConfig.isEnabled()) {
                membershipService.stop();
                if (clusterServer != null) {
                    clusterServer.stop();
                }
            }
        });
        logger.info("Shutdown complete");
    }

    private void step(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.warning("Shutdown step '" + description + "' failed: " + e.getMessage());
        }
    }

    private static long remaining(long deadline) {
        return Math.max(200L, deadline - System.currentTimeMillis());
    }
}
