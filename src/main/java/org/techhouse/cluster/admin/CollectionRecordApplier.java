package org.techhouse.cluster.admin;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.HybridClock;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.admin.GrantPruner;
import org.techhouse.ops.admin.LeftoverFolders;

final class CollectionRecordApplier {
    private static final Logger logger = Logger.logFor(CollectionRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final AdminQuarantine quarantine = new AdminQuarantine();

    private CollectionRecordApplier() {
    }

    static boolean install(AdminRecord record, boolean live, Map<String, Long> tombstones) throws Exception {
        final var dbName = record.key().dbName();
        final var collName = record.key().name();
        final var sibling = caseVariantOf(dbName, collName);
        final var resolution = CaseVariant.resolve(record, sibling, tombstones,
                variant -> remove(dbName, variant.name(), live));
        if (resolution != CaseVariant.Resolution.INSTALL) {
            return resolution == CaseVariant.Resolution.BURIED;
        }
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockDatabaseShared(dbName, waitMillis)) {
            logger.warning(skipping(dbName, collName, "its database stayed locked by a drop"));
            return false;
        }
        try {
            if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
                logger.warning(skipping(dbName, collName, "its write lock stayed held"));
                return false;
            }
            try {
                return installUnderLocks(record, AdminCollEntry.fromJsonObject(record.body()));
            } finally {
                locks.release(dbName, collName);
            }
        } finally {
            locks.releaseDatabaseShared(dbName);
        }
    }

    private static boolean installUnderLocks(AdminRecord record, AdminCollEntry desired) throws Exception {
        final var dbName = record.key().dbName();
        final var collName = record.key().name();
        final var incarnation = desired.getIncarnation();
        if (incarnation != 0) {
            hybridClock.observe(incarnation);
        }
        quarantine.quarantineStaleIncarnation(dbName, collName, incarnation);
        if (!LeftoverFolders.moveAsideUnregisteredCollection(dbName, collName)) {
            return false;
        }
        fs.createCollectionFile(dbName, collName);
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            AdminOperationHelper.createPageCollections(dbName, collName);
        }
        final var existing = new HashSet<>(cache.getIndexesForCollection(dbName, collName));
        final var desiredIndexes = new HashSet<>(desired.getIndexes());
        final var added = new HashSet<>(desiredIndexes);
        added.removeAll(existing);
        final var removed = new HashSet<>(existing);
        removed.removeAll(desiredIndexes);
        markAll(dbName, collName, added, removed);
        final var installed = new AdminCollEntry(dbName, collName, desiredIndexes);
        installed.setIncarnation(incarnation);
        installed.setVersion(record.version());
        AdminOperationHelper.saveCollectionEntry(installed);
        for (final var field : added) {
            IndexHelper.createIndex(dbName, collName, field);
        }
        for (final var field : removed) {
            IndexHelper.dropIndex(dbName, collName, field);
        }
        clearAll(dbName, collName, added, removed);
        return true;
    }

    private static void markAll(String dbName, String collName, Set<String> added, Set<String> removed)
            throws IOException {
        for (final var field : added) {
            fs.indexBuildMarkers().mark(dbName, collName, field);
        }
        for (final var field : removed) {
            fs.indexBuildMarkers().mark(dbName, collName, field);
        }
    }

    private static void clearAll(String dbName, String collName, Set<String> added, Set<String> removed) {
        added.forEach(field -> fs.indexBuildMarkers().clear(dbName, collName, field));
        removed.forEach(field -> fs.indexBuildMarkers().clear(dbName, collName, field));
    }

    private static AdminRecordKey caseVariantOf(String dbName, String collName) {
        final var sibling = OnDiskNameRegistry.collidingCollection(dbName, collName);
        if (sibling == null || sibling.equals(collName) || cache.getAdminCollectionEntry(dbName, sibling) == null) {
            return null;
        }
        return AdminRecordKey.collection(dbName, sibling);
    }

    static boolean remove(String dbName, String collName, boolean live) throws Exception {
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return true;
        }
        if (live) {
            return CollectionOperationHelper.dropCollection(dbName, collName, true).getStatus() == OperationStatus.OK;
        }
        if (!quarantine.dropCollection(dbName, collName, clusterConfig.replicationAckTimeoutMs())) {
            return false;
        }
        GrantPruner.forDroppedCollection(dbName, collName);
        return true;
    }

    private static String skipping(String dbName, String collName, String reason) {
        return "Skipping the install of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + ": " + reason
                + " for " + clusterConfig.replicationAckTimeoutMs() + "ms. The next round retries it.";
    }
}
