package org.techhouse.listen;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;

public class ListenManager {
    public static final String COLLECTION_DROPPED = "a collection it reads was dropped";
    public static final String DATABASE_DROPPED = "the database it reads was dropped";
    public static final String ACCESS_REVOKED = "the listening user can no longer read it";

    record EndedListen(ListenRegistration registration, String reason) {
    }

    private final Logger logger = Logger.logFor(ListenManager.class);
    private final Configuration configuration = Configuration.getInstance();
    private final ListenRetention retention = new ListenRetention();
    private final Map<UUID, ListenRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<UUID, EndedListen> ended = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> collectionToListens = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<UUID> dirtyQueue = new LinkedBlockingQueue<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Set<String>> deferred = new ThreadLocal<>();
    private final ThreadLocal<Integer> deferralDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Set<String>> applyingKeys = new ThreadLocal<>();
    private final ApplyTracker applyTracker = new ApplyTracker();
    private final Set<UUID> heldBack = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public boolean hasCapacityFor(UUID clientId) {
        final var globalLimit = configuration.intValue(ConfigKey.MAX_LISTENS);
        if (globalLimit != 0 && registrations.size() >= globalLimit) {
            return false;
        }
        final var perConnection = configuration.intValue(ConfigKey.MAX_LISTENS_PER_CONNECTION);
        return perConnection == 0
                || registrations.values().stream().filter(registration -> registration.clientId().equals(clientId))
                        .limit(perConnection).count() < perConnection;
    }

    public boolean hasUserCapacity(String username) {
        return retention.hasUserCapacity(username);
    }

    public ErrorCode reserve(String username, long retainedBytes) {
        return retention.reserve(username, retainedBytes);
    }

    public void release(String username, long retainedBytes) {
        retention.release(username, retainedBytes);
    }

    public long retainedBytes() {
        return retention.retainedBytes();
    }

    public int listensOf(String username) {
        return retention.listensOf(username);
    }

    public UUID register(UUID clientId, AggregateRequest dirtyRequest, String initialHash) {
        return register(clientId, null, dirtyRequest, initialHash, 0L);
    }

    public UUID register(UUID clientId, String username, AggregateRequest dirtyRequest, String initialHash,
            long retainedBytes) {
        final var listenId = UUID.randomUUID();
        final var keys = collectKeys(dirtyRequest);
        final var registration = new ListenRegistration(listenId, clientId, dirtyRequest, keys,
                new AtomicReference<>(initialHash), new AtomicBoolean(false), username, retainedBytes);
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
        releaseRetention(registration);
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
        return removeEnded(listenId, endedListen);
    }

    private boolean removeEnded(UUID listenId, EndedListen endedListen) {
        if (!ended.remove(listenId, endedListen)) {
            return false;
        }
        releaseRetention(endedListen.registration());
        return true;
    }

    private void releaseRetention(ListenRegistration registration) {
        retention.release(registration.username(), registration.retainedBytes());
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
        for (var entry : List.copyOf(ended.entrySet())) {
            if (entry.getValue().registration().clientId().equals(clientId)) {
                removeEnded(entry.getKey(), entry.getValue());
            }
        }
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
        deferNotifications(List.of());
    }

    public void deferNotifications(Collection<String> collectionKeys) {
        if (deferralDepth.get() == 0) {
            deferred.set(new LinkedHashSet<>());
            applyingKeys.set(new LinkedHashSet<>());
        }
        deferralDepth.set(deferralDepth.get() + 1);
        final var newlyApplying = new LinkedHashSet<>(collectionKeys);
        newlyApplying.removeAll(applyingKeys.get());
        applyingKeys.get().addAll(newlyApplying);
        applyTracker.begin(newlyApplying);
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
        final var applied = applyingKeys.get();
        applyingKeys.remove();
        if (applied != null) {
            applyTracker.end(applied);
        }
        if (pending != null) {
            for (final var key : pending) {
                enqueueDirty(key);
            }
        }
        releaseHeldBack();
    }

    ApplyTracker.ApplySnapshot applySnapshot(Set<String> collectionKeys) {
        return applyTracker.snapshot(collectionKeys);
    }

    boolean unchangedSince(ApplyTracker.ApplySnapshot snapshot) {
        return applyTracker.unchangedSince(snapshot);
    }

    void holdBack(UUID listenId, Set<String> collectionKeys) {
        heldBack.add(listenId);
        if (!applyTracker.anyActive(collectionKeys) && heldBack.remove(listenId)) {
            enqueue(listenId);
        }
    }

    private void releaseHeldBack() {
        for (final var listenId : List.copyOf(heldBack)) {
            if (heldBack.remove(listenId)) {
                enqueue(listenId);
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
        heldBack.clear();
        applyTracker.clear();
        for (var listenId : List.copyOf(registrations.keySet())) {
            unregister(listenId);
        }
        for (var entry : List.copyOf(ended.entrySet())) {
            removeEnded(entry.getKey(), entry.getValue());
        }
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
