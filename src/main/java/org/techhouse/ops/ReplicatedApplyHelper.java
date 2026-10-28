package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cache.PkIndexList;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.PeerNames;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.TombstoneView;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.DirtySink;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.NotificationBatch;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;

// Never re-replicates: replication fires only from the owner's OperationProcessor write handlers.
public final class ReplicatedApplyHelper {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final Logger logger = Logger.logFor(ReplicatedApplyHelper.class);

    private ReplicatedApplyHelper() {
    }

    public static boolean apply(ReplicationPayload payload, long timeoutMillis) {
        if (payload == null || payload.getOp() == null
                || !PeerNames.collection(payload.getDbName(), payload.getCollName())) {
            return false;
        }
        final var dbName = payload.getDbName();
        final var collName = payload.getCollName();
        var locked = false;
        try {
            locked = locks.tryLockWrite(dbName, collName, timeoutMillis);
            if (!locked) {
                logger.warning("Skipping the replicated apply to " + dbName + "|" + collName
                        + ": its write lock stayed held for " + timeoutMillis + "ms");
                return false;
            }
            faultInjector.at(FaultPoint.REPLICATED_APPLY_LOCKED, Cache.getCollectionIdentifier(dbName, collName));
            return applyLocked(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted applying a replicated write to " + dbName + "|" + collName);
            return false;
        } catch (Exception e) {
            logger.error("Failed to apply replicated write to " + dbName + "|" + collName, e);
            return false;
        } finally {
            if (locked) {
                locks.release(dbName, collName);
            }
        }
    }

    static boolean applyLocked(ReplicationPayload payload) throws Exception {
        return applyWithin(payload, listenManager);
    }

    static boolean applyLocked(ReplicationPayload payload, NotificationBatch transactionBatch) throws Exception {
        return applyWithin(payload, transactionBatch);
    }

    private static boolean applyWithin(ReplicationPayload payload, DirtySink outer) throws Exception {
        if (!versionsWithinSkew(payload)) {
            return false;
        }
        if (belongsToAnotherIncarnation(payload)) {
            logger.warning("Refusing a replicated " + payload.getOp() + " to " + payload.getDbName() + "|"
                    + payload.getCollName() + ": it belongs to incarnation " + payload.incarnationValue()
                    + " and this node holds incarnation "
                    + CollectionIncarnation.current(payload.getDbName(), payload.getCollName()));
            return false;
        }
        try (var batch = listenManager.openBatch(outer,
                List.of(Cache.getCollectionIdentifier(payload.getDbName(), payload.getCollName())))) {
            return switch (payload.getOp()) {
                case UPSERT -> applyUpsert(payload, batch);
                case DELETE -> applyDelete(payload, batch);
            };
        }
    }

    public static boolean belongsToAnotherIncarnation(ReplicationPayload payload) {
        final var stamped = payload.incarnationValue();
        return stamped != 0L && !CollectionIncarnation.isCurrent(payload.getDbName(), payload.getCollName(), stamped);
    }

    private static boolean applyUpsert(ReplicationPayload payload, DirtySink sink) throws Exception {
        final var documents = payload.getDocuments();
        final var versions = payload.getVersions();
        final var acceptedDocuments = new ArrayList<JsonObject>();
        final var acceptedVersions = new ArrayList<Long>();
        final var tombstones = fs.tombstones().view(payload.getDbName(), payload.getCollName());
        for (var i = 0; i < documents.size(); i++) {
            final var document = documents.get(i);
            final var version = versionAt(versions, i);
            if (version == null) {
                acceptedDocuments.add(document);
                acceptedVersions.add(null);
                continue;
            }
            hybridClock.observe(version);
            if (isSupersededLocally(payload.getDbName(), payload.getCollName(), document, version, tombstones)) {
                continue;
            }
            acceptedDocuments.add(document);
            acceptedVersions.add(version);
        }
        if (acceptedDocuments.isEmpty()) {
            return true;
        }
        final var request = new BulkSaveRequest(payload.getDbName(), payload.getCollName());
        request.setObjects(acceptedDocuments);
        return SaveOperationHelper.executeBulkSave(request, acceptedVersions, sink,
                servesCollection(payload.getDbName(), payload.getCollName())) instanceof BulkSaveResponse;
    }

    public static boolean versionsWithinSkew(ReplicationPayload payload) {
        if (payload.getVersions() == null) {
            return true;
        }
        for (final var raw : payload.getVersions()) {
            if (raw != null && !hybridClock.withinSkew(Long.parseLong(raw), clusterConfig.maxClockSkewMs())) {
                logger.warning("Refusing a replicated " + payload.getOp() + " to " + payload.getDbName() + "|"
                        + payload.getCollName() + ": its version " + raw
                        + " is more than clusterMaxClockSkewMs ahead of this node's clock");
                return false;
            }
        }
        return true;
    }

    private static boolean isSupersededLocally(String dbName, String collName, JsonObject document, long version,
            TombstoneView tombstones) throws java.io.IOException {
        final var idElement = document.get(Globals.PK_FIELD);
        if (!(idElement instanceof JsonString jsonString)) {
            return false;
        }
        final var id = jsonString.getValue();
        final var stored = storedVersionOf(dbName, collName, id);
        if (stored != null && stored > version) {
            return true;
        }
        final var tombstoned = tombstones.versionOf(id);
        return tombstoned != 0L && tombstoned >= version;
    }

    private static Long storedVersionOf(String dbName, String collName, String id) throws java.io.IOException {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var found = Collections.binarySearch(primaryKeyIndex, id);
        return found >= 0 ? primaryKeyIndex.get(found).getVersion() : null;
    }

    private static Long versionAt(List<String> versions, int index) {
        if (versions == null || index >= versions.size()) {
            return null;
        }
        final var raw = versions.get(index);
        return raw == null ? null : Long.valueOf(raw);
    }

    private static boolean applyDelete(ReplicationPayload payload, DirtySink sink) throws Exception {
        final var dbName = payload.getDbName();
        final var collName = payload.getCollName();
        final var ids = payload.getIds();
        final var versions = payload.getVersions();
        final var tombstones = fs.tombstones().view(dbName, collName);
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var missingIds = new ArrayList<String>();
        final var missingVersions = new ArrayList<Long>();
        final var deletable = new ArrayList<String>(ids.size());
        for (var i = 0; i < ids.size(); i++) {
            final var id = ids.get(i);
            final var version = versionAt(versions, i);
            if (version != null) {
                hybridClock.observe(version);
                if (tombstones.versionOf(id) < version) {
                    missingIds.add(id);
                    missingVersions.add(version);
                }
            }
            final var stored = PkIndexList.rowOf(primaryKeyIndex, id);
            if (version == null || stored == null || stored.getVersion() <= version) {
                deletable.add(id);
            }
        }
        final var pending = fs.tombstones().appendAllDeferred(dbName, collName, missingIds, missingVersions);
        faultInjector.at(FaultPoint.REPLICATED_TOMBSTONES_APPENDED, Cache.getCollectionIdentifier(dbName, collName));
        DeleteOperationHelper.executeBulkDelete(dbName, collName, deletable, sink, servesCollection(dbName, collName));
        pending.await();
        return true;
    }

    private static boolean servesCollection(String dbName, String collName) {
        return coordinator.stillOwns(dbName, collName);
    }
}
