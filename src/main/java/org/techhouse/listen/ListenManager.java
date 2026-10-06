package org.techhouse.listen;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.techhouse.bckg_ops.RestartablePool;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;

public class ListenManager {
    public static final String COLLECTION_DROPPED = "a collection it reads was dropped";
    public static final String DATABASE_DROPPED = "the database it reads was dropped";
    public static final String ACCESS_REVOKED = "the listening user can no longer read it";

    record EndedListen(ListenRegistration registration, String reason) {
    }

    private final Logger logger = Logger.logFor(ListenManager.class);
    private final Map<UUID, ListenRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<UUID, EndedListen> ended = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> collectionToListens = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<UUID> dirtyQueue = new LinkedBlockingQueue<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Set<String>> deferred = new ThreadLocal<>();
    private final ThreadLocal<Integer> deferralDepth = ThreadLocal.withInitial(() -> 0);
    private volatile ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public UUID register(UUID clientId, AggregateRequest dirtyRequest, String initialHash) {
        final var listenId = UUID.randomUUID();
        final var keys = collectKeys(dirtyRequest);
        final var registration = new ListenRegistration(listenId, clientId, dirtyRequest, keys,
                new AtomicReference<>(initialHash), new AtomicBoolean(false));
        registrations.put(listenId, registration);
        for (var key : keys) {
            collectionToListens.computeIfAbsent(key, _ -> ConcurrentHashMap.newKeySet()).add(listenId);
        }
        return listenId;
    }

    public void markDelivered(UUID listenId) {
        var registration = registrations.get(listenId);
        if (registration == null) {
            final var endedListen = ended.get(listenId);
            registration = endedListen != null ? endedListen.registration() : null;
        }
        if (registration == null) {
            return;
        }
        registration.delivered().set(true);
        enqueue(listenId);
    }

    public boolean unregister(UUID listenId, UUID clientId) {
        final var registration = registrations.get(listenId);
        if (registration == null || !registration.clientId().equals(clientId)) {
            return false;
        }
        return unregister(listenId);
    }

    public boolean unregister(UUID listenId) {
        final var registration = registrations.remove(listenId);
        if (registration == null) {
            return false;
        }
        detachKeys(registration);
        return true;
    }

    public boolean end(UUID listenId, String reason) {
        final var moved = new AtomicReference<ListenRegistration>();
        registrations.computeIfPresent(listenId, (id, registration) -> {
            ended.put(id, new EndedListen(registration, reason));
            moved.set(registration);
            return null;
        });
        final var registration = moved.get();
        if (registration == null) {
            return false;
        }
        detachKeys(registration);
        enqueue(listenId);
        return true;
    }

    EndedListen endedListen(UUID listenId) {
        return ended.get(listenId);
    }

    boolean consumeEnded(UUID listenId, EndedListen endedListen) {
        return ended.remove(listenId, endedListen);
    }

    private void detachKeys(ListenRegistration registration) {
        for (var key : registration.collectionKeys()) {
            final var listenIds = collectionToListens.get(key);
            if (listenIds != null) {
                listenIds.remove(registration.listenId());
            }
        }
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
        ended.values().removeIf(endedListen -> endedListen.registration().clientId().equals(clientId));
    }

    public void endAllForCollection(String dbName, String collName, String reason) {
        final var key = dbName + "|" + collName;
        final var listenIds = collectionToListens.get(key);
        if (listenIds == null || listenIds.isEmpty()) {
            return;
        }
        for (var listenId : new HashSet<>(listenIds)) {
            end(listenId, reason);
        }
    }

    public void endAllForDatabase(String dbName, String reason) {
        final var prefix = dbName + "|";
        final var toRemove = new HashSet<UUID>();
        for (var entry : collectionToListens.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                toRemove.addAll(entry.getValue());
            }
        }
        for (var listenId : toRemove) {
            end(listenId, reason);
        }
    }

    public void deferNotifications() {
        if (deferralDepth.get() == 0) {
            deferred.set(new LinkedHashSet<>());
        }
        deferralDepth.set(deferralDepth.get() + 1);
    }

    public void flushDeferredNotifications() {
        final var depth = deferralDepth.get();
        if (depth == 0) {
            return;
        }
        if (depth > 1) {
            deferralDepth.set(depth - 1);
            return;
        }
        deferralDepth.remove();
        final var pending = deferred.get();
        deferred.remove();
        if (pending != null) {
            for (final var key : pending) {
                enqueueDirty(key);
            }
        }
    }

    public void markDirty(String dbName, String collName) {
        final var key = dbName + "|" + collName;
        final var pending = deferred.get();
        if (pending != null) {
            pending.add(key);
            return;
        }
        enqueueDirty(key);
    }

    private void enqueueDirty(String key) {
        final var listenIds = collectionToListens.get(key);
        if (listenIds == null || listenIds.isEmpty()) {
            return;
        }
        for (var listenId : listenIds) {
            enqueue(listenId);
        }
    }

    private void enqueue(UUID listenId) {
        if (queued.add(listenId)) {
            dirtyQueue.offer(listenId);
        }
    }

    void dequeued(UUID listenId) {
        queued.remove(listenId);
    }

    public ListenRegistration getRegistration(UUID listenId) {
        return registrations.get(listenId);
    }

    public synchronized void startWorkers() {
        pool.execute(new ListenProcessorThread(dirtyQueue, this));
        logger.info("Started listen processor worker");
    }

    public synchronized void stopWorkers() {
        pool = RestartablePool.shutdownAndReplace(pool, logger, "Listen");
        dirtyQueue.clear();
        queued.clear();
        registrations.clear();
        ended.clear();
        collectionToListens.clear();
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
