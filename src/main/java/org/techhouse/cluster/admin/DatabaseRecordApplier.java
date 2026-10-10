package org.techhouse.cluster.admin;

import java.util.ArrayList;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.admin.GrantPruner;
import org.techhouse.ops.admin.LeftoverFolders;

final class DatabaseRecordApplier {
    private static final Logger logger = Logger.logFor(DatabaseRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final AdminQuarantine quarantine = new AdminQuarantine();

    private DatabaseRecordApplier() {
    }

    static boolean install(AdminRecord record, boolean live, Map<String, Long> tombstones) throws Exception {
        final var dbName = record.key().dbName();
        final var sibling = caseVariantOf(dbName);
        final var resolution = CaseVariant.resolve(record, sibling, tombstones,
                variant -> remove(variant.dbName(), live));
        if (resolution != CaseVariant.Resolution.INSTALL) {
            return resolution == CaseVariant.Resolution.BURIED;
        }
        if (cache.getAdminDbEntry(dbName) == null && !clearedOfLeftovers(dbName)) {
            return false;
        }
        fs.createDatabaseFolder(dbName);
        final var local = cache.getAdminDbEntry(dbName);
        final var owners = AdminDbEntry.fromJsonObject(record.body()).getOwners();
        final var installed = new AdminDbEntry(dbName,
                local == null ? new ArrayList<>() : new ArrayList<>(local.getCollections()), new ArrayList<>(owners));
        installed.setVersion(record.version());
        AdminOperationHelper.saveDatabaseEntry(installed);
        return true;
    }

    private static AdminRecordKey caseVariantOf(String dbName) {
        final var sibling = OnDiskNameRegistry.collidingDatabase(dbName);
        if (sibling == null || sibling.equals(dbName) || cache.getAdminDbEntry(sibling) == null) {
            return null;
        }
        return AdminRecordKey.database(sibling);
    }

    static boolean remove(String dbName, boolean live) throws Exception {
        if (cache.getAdminDbEntry(dbName) == null) {
            return true;
        }
        if (live) {
            return DatabaseOperationHelper.dropDatabase(dbName, true).getStatus() == OperationStatus.OK;
        }
        if (!quarantine.dropDatabase(dbName, clusterConfig.replicationAckTimeoutMs())) {
            return false;
        }
        GrantPruner.forDroppedDatabase(dbName);
        return true;
    }

    private static boolean clearedOfLeftovers(String dbName) throws InterruptedException {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockDatabaseExclusive(dbName, waitMillis)) {
            logger.warning("Skipping the install of database " + dbName + ": it stayed locked for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            return LeftoverFolders.moveAsideUnregisteredDatabase(dbName);
        } finally {
            locks.releaseDatabaseExclusive(dbName);
        }
    }
}
