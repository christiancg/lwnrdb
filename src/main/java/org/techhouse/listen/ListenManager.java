package org.techhouse.listen;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;

public class ListenManager {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3L;
    private final Logger logger = Logger.logFor(ListenManager.class);
    private final Map<UUID, ListenRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> collectionToListens = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<UUID> dirtyQueue = new LinkedBlockingQueue<>();
    private ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public UUID register(UUID clientId, AggregateRequest dirtyRequest, String initialHash) {
        final var listenId = UUID.randomUUID();
        final var keys = collectKeys(dirtyRequest);
        final var registration = new ListenRegistration(listenId, clientId, dirtyRequest, keys,
                new AtomicReference<>(initialHash));
        registrations.put(listenId, registration);
        for (var key : keys) {
            collectionToListens.computeIfAbsent(key, _ -> ConcurrentHashMap.newKeySet()).add(listenId);
        }
        return listenId;
    }

    public boolean unregister(UUID listenId) {
        final var registration = registrations.remove(listenId);
        if (registration == null) {
            return false;
        }
        for (var key : registration.collectionKeys()) {
            final var listenIds = collectionToListens.get(key);
            if (listenIds != null) {
                listenIds.remove(listenId);
            }
        }
        return true;
    }

    public void unregisterAllForClient(UUID clientId) {
        final var toRemove = new HashSet<UUID>();
        for (var entry : registrations.entrySet()) {
            if (entry.getValue().clientId().equals(clientId)) {
                toRemove.add(entry.getKey());
            }
        }
        for (var listenId : toRemove) {
            unregister(listenId);
        }
    }

    public void unregisterAllForCollection(String dbName, String collName) {
        final var key = dbName + "|" + collName;
        final var listenIds = collectionToListens.get(key);
        if (listenIds == null || listenIds.isEmpty()) {
            return;
        }
        for (var listenId : new HashSet<>(listenIds)) {
            unregister(listenId);
        }
    }

    public void unregisterAllForDatabase(String dbName) {
        final var prefix = dbName + "|";
        final var toRemove = new HashSet<UUID>();
        for (var entry : collectionToListens.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                toRemove.addAll(entry.getValue());
            }
        }
        for (var listenId : toRemove) {
            unregister(listenId);
        }
    }

    public void markDirty(String dbName, String collName) {
        final var key = dbName + "|" + collName;
        final var listenIds = collectionToListens.get(key);
        if (listenIds == null || listenIds.isEmpty()) {
            return;
        }
        for (var listenId : listenIds) {
            dirtyQueue.offer(listenId);
        }
    }

    public ListenRegistration getRegistration(UUID listenId) {
        return registrations.get(listenId);
    }

    public void startWorkers() {
        pool.execute(new ListenProcessorThread(dirtyQueue, this));
        logger.info("Started listen processor worker");
    }

    public void stopWorkers() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Listen workers did not terminate within the timeout; abandoning them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dirtyQueue.clear();
        registrations.clear();
        collectionToListens.clear();
        pool = Executors.newVirtualThreadPerTaskExecutor();
        logger.info("Stopped listen processor worker");
    }

    private static Set<String> collectKeys(AggregateRequest request) {
        final var keys = new HashSet<String>();
        keys.add(request.getDatabaseName() + "|" + request.getCollectionName());
        if (request.getAggregationSteps() != null) {
            for (var step : request.getAggregationSteps()) {
                if (step instanceof JoinAggregationStep joinStep) {
                    keys.add(request.getDatabaseName() + "|" + joinStep.getJoinCollection());
                }
            }
        }
        return keys;
    }
}
