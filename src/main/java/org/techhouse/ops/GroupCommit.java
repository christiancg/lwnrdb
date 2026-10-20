package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;

final class GroupCommit {
    static final int MAX_GROUP_OPS = 64;
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(GroupCommit.class);
    private static final Map<String, Queue<GroupedWrite>> queues = new ConcurrentHashMap<>();

    private enum Acquired {
        HELD, HELD_AFTER_INTERRUPT, WITHDRAWN
    }

    static final class GroupedWrite {
        private final OperationType type;
        private final ErrorCode errorCode;
        private final BooleanSupplier groupable;
        private final OperationResponse.Attempt attempt;
        private OperationResponse response;
        private boolean served;

        GroupedWrite(OperationType type, ErrorCode errorCode, BooleanSupplier groupable,
                OperationResponse.Attempt attempt) {
            this.type = type;
            this.errorCode = errorCode;
            this.groupable = groupable;
            this.attempt = attempt;
        }

        private OperationResponse runAlone() {
            return OperationResponse.respondOrError(type, errorCode, attempt);
        }

        private void serve(OperationResponse result) {
            response = result;
            served = true;
        }

        private OperationResponse failure() {
            return new OperationResponse(type, errorCode);
        }
    }

    private GroupCommit() {
    }

    static boolean enabled() {
        return configuration.isSyncWrites() && !clusterConfig.isEnabled();
    }

    static boolean groupable(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    static BooleanSupplier forSave(SaveRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var depth = request.getTriggerDepth();
        return () -> depth == 0 && !request.isBackgroundFollowOn()
                && !TriggerHelper.firesOnSave(dbName, collName, depth)
                && !BeforeHookContext.hasHooksFor(dbName, collName, EventType.CREATED)
                && !BeforeHookContext.hasHooksFor(dbName, collName, EventType.UPDATED);
    }

    static BooleanSupplier forDelete(DeleteRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var depth = request.getTriggerDepth();
        return () -> depth == 0 && !TriggerHelper.firesOnDelete(dbName, collName, depth)
                && !BeforeHookContext.hasHooksFor(dbName, collName, EventType.DELETED);
    }

    static OperationResponse run(String dbName, String collName, GroupedWrite write) {
        final var queue = queueOf(dbName, collName);
        queue.add(write);
        final var acquired = acquire(dbName, collName, queue, write);
        if (acquired == Acquired.WITHDRAWN) {
            return write.failure();
        }
        try {
            while (!write.served) {
                lead(dbName, collName, queue);
            }
            return write.response;
        } finally {
            locks.release(dbName, collName);
            if (acquired == Acquired.HELD_AFTER_INTERRUPT) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static int queuedFor(String dbName, String collName) {
        return queueOf(dbName, collName).size();
    }

    private static Queue<GroupedWrite> queueOf(String dbName, String collName) {
        return queues.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ConcurrentLinkedQueue<>());
    }

    private static Acquired acquire(String dbName, String collName, Queue<GroupedWrite> queue, GroupedWrite write) {
        var interrupted = false;
        while (true) {
            try {
                locks.lock(dbName, collName);
                return interrupted ? Acquired.HELD_AFTER_INTERRUPT : Acquired.HELD;
            } catch (InterruptedException e) {
                if (queue.remove(write)) {
                    Thread.currentThread().interrupt();
                    return Acquired.WITHDRAWN;
                }
                interrupted = true;
            }
        }
    }

    private static void lead(String dbName, String collName, Queue<GroupedWrite> queue) {
        final var grouped = new ArrayList<GroupedWrite>();
        final var alone = new ArrayList<GroupedWrite>();
        while (grouped.size() + alone.size() < MAX_GROUP_OPS) {
            final var next = queue.poll();
            if (next == null) {
                break;
            }
            (groupable(next.groupable) ? grouped : alone).add(next);
        }
        runGrouped(dbName, collName, grouped);
        alone.forEach(write -> write.serve(write.runAlone()));
    }

    private static void runGrouped(String dbName, String collName, List<GroupedWrite> writes) {
        if (writes.isEmpty()) {
            return;
        }
        try {
            fs.openWriteGroup(dbName, collName);
        } catch (IOException e) {
            logger.warning("Could not open a write group on " + Cache.getCollectionIdentifier(dbName, collName)
                    + "; its writes run one by one: " + e.getMessage());
            writes.forEach(write -> write.serve(write.runAlone()));
            return;
        }
        final var responses = new ArrayList<OperationResponse>(writes.size());
        final boolean durable;
        try (var ignored = listenManager.openBatch(List.of(Cache.getCollectionIdentifier(dbName, collName)))) {
            for (final var write : writes) {
                responses.add(write.runAlone());
            }
            durable = settle(dbName, collName);
            if (!durable) {
                forgetTakenBack(dbName, collName);
            }
        }
        for (var index = 0; index < writes.size(); index++) {
            final var write = writes.get(index);
            final var response = responses.get(index);
            write.serve(durable || response.getStatus() != OperationStatus.OK ? response : write.failure());
        }
    }

    private static boolean settle(String dbName, String collName) {
        try {
            return fs.settleWriteGroup(dbName, collName);
        } catch (IOException e) {
            logger.error("Could not settle the write group of " + Cache.getCollectionIdentifier(dbName, collName), e);
            return false;
        }
    }

    private static void forgetTakenBack(String dbName, String collName) {
        cache.evictCollection(dbName, collName);
        try {
            PageOccupancyReconciler.reconcile(dbName, collName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("Could not reconcile the page rows of " + Cache.getCollectionIdentifier(dbName, collName)
                    + " after a failed write group; the next startup rebuilds them: " + e.getMessage());
        }
    }
}
