package org.techhouse.cluster.admin;

import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.admin.AdminRecordKey;

final class DefinitionRecordApplier {
    private static final Logger logger = Logger.logFor(DefinitionRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);

    private DefinitionRecordApplier() {
    }

    private interface FileChange {
        void apply() throws Exception;
    }

    static boolean install(AdminRecord record) throws Exception {
        final var key = record.key();
        final var json = eJson.toJson(record.body());
        return underLock(key, () -> {
            switch (key.kind()) {
                case SCHEMA -> fs.writeCollectionSchema(key.dbName(), key.name(), json);
                case TRIGGERS -> fs.writeTriggers(key.dbName(), key.name(), json);
                case PROCEDURE -> fs.writeProcedure(key.dbName(), key.name(), json);
                default -> fs.writeSchedule(key.dbName(), key.name(), json);
            }
            invalidate(key);
        });
    }

    static boolean remove(AdminRecordKey key) throws Exception {
        return underLock(key, () -> {
            switch (key.kind()) {
                case SCHEMA -> fs.deleteCollectionSchema(key.dbName(), key.name());
                case TRIGGERS -> fs.deleteTriggers(key.dbName(), key.name());
                case PROCEDURE -> fs.deleteProcedure(key.dbName(), key.name());
                default -> fs.deleteSchedule(key.dbName(), key.name());
            }
            invalidate(key);
        });
    }

    private static void invalidate(AdminRecordKey key) {
        switch (key.kind()) {
            case SCHEMA -> cache.removeCollectionSchema(key.dbName(), key.name());
            case TRIGGERS -> cache.removeTriggers(key.dbName(), key.name());
            case PROCEDURE -> {
                cache.removeProcedure(key.dbName(), key.name());
                compiledProcedures.invalidateProcedure(key.dbName(), key.name());
            }
            default -> {
                cache.removeSchedule(key.dbName(), key.name());
                scheduleRegistry.reload(key.dbName());
            }
        }
    }

    private static String lockName(AdminRecordKey key) {
        return switch (key.kind()) {
            case PROCEDURE -> Globals.PROCEDURES_FOLDER;
            case SCHEDULE -> Globals.SCHEDULES_FOLDER;
            default -> key.name();
        };
    }

    private static boolean underLock(AdminRecordKey key, FileChange change) throws Exception {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        final var lockName = lockName(key);
        if (!locks.tryLockWrite(key.dbName(), lockName, waitMillis)) {
            logger.warning("Skipping " + key.id() + ": its lock stayed held for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            change.apply();
            return true;
        } finally {
            locks.release(key.dbName(), lockName);
        }
    }
}
