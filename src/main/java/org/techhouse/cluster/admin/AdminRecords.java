package org.techhouse.cluster.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.AdminTombstone;
import org.techhouse.ops.admin.StoredDefinitions;

public final class AdminRecords {
    private static final Logger logger = Logger.logFor(AdminRecords.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private AdminRecords() {
    }

    public static List<AdminRecord> all() throws IOException {
        final var tombstones = AdminTombstone.all();
        final var keys = new ArrayList<AdminRecordKey>();
        for (final var dbName : cache.getUserDatabaseNames()) {
            keys.add(AdminRecordKey.database(dbName));
            for (final var collName : cache.getCollectionNamesForDatabase(dbName)) {
                keys.add(AdminRecordKey.collection(dbName, collName));
                keys.add(AdminRecordKey.schema(dbName, collName));
                keys.add(AdminRecordKey.triggers(dbName, collName));
            }
            listed(dbName, Globals.PROCEDURES_FOLDER, () -> fs.listProcedureNames(dbName))
                    .forEach(name -> keys.add(AdminRecordKey.procedure(dbName, name)));
            listed(dbName, Globals.SCHEDULES_FOLDER, () -> fs.listScheduleNames(dbName))
                    .forEach(name -> keys.add(AdminRecordKey.schedule(dbName, name)));
        }
        for (final var user : cache.getAllAdminUserEntries()) {
            keys.add(AdminRecordKey.user(user.get_id()));
        }
        tombstones.keySet().forEach(id -> keys.add(AdminRecordKey.parse(id)));
        final var records = new ArrayList<AdminRecord>();
        for (final var key : keys.stream().distinct().toList()) {
            final var record = current(key, tombstones);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    public static List<AdminRecord> of(List<AdminRecordKey> keys) throws IOException {
        final var tombstones = AdminTombstone.all();
        final var records = new ArrayList<AdminRecord>();
        for (final var key : keys) {
            final var record = current(key, tombstones);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    public static AdminRecord current(AdminRecordKey key, Map<String, Long> tombstones) {
        final var live = live(key);
        final var tombstoneVersion = tombstones.getOrDefault(key.id(), 0L);
        if (tombstones.containsKey(key.id()) && (live == null || tombstoneVersion >= live.version())) {
            return AdminRecord.tombstone(key, tombstoneVersion);
        }
        return live;
    }

    public static AdminRecord live(AdminRecordKey key) {
        try {
            return switch (key.kind()) {
                case DATABASE ->
                    row(key, cache.getAdminDbEntry(key.dbName()), cache.getPkIndexAdminDbEntry(key.dbName()));
                case COLLECTION -> collection(key);
                case USER -> row(key, cache.getAdminUserEntry(key.name()), cache.getPkIndexAdminUserEntry(key.name()));
                case SCHEMA -> stored(key, StoredDefinitions.schema(key.dbName(), key.name()));
                case TRIGGERS -> stored(key, StoredDefinitions.triggers(key.dbName(), key.name()));
                case PROCEDURE -> stored(key, StoredDefinitions.procedure(key.dbName(), key.name()));
                case SCHEDULE -> stored(key, StoredDefinitions.schedule(key.dbName(), key.name()));
            };
        } catch (IOException | RuntimeException unreadable) {
            logger.warning("Leaving " + key.id() + " out of the admin records: it could not be read: "
                    + unreadable.getMessage());
            return null;
        }
    }

    private static AdminRecord collection(AdminRecordKey key) {
        final var entry = cache.getAdminCollectionEntry(key.dbName(), key.name());
        if (entry == null) {
            return null;
        }
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return new AdminRecord(key, AdminOperationHelper.versionOf(
                cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(key.dbName(), key.name()))), body);
    }

    private static AdminRecord row(AdminRecordKey key, DbEntry entry, PkIndexEntry pk) {
        if (entry == null) {
            return null;
        }
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return new AdminRecord(key, AdminOperationHelper.versionOf(pk), body);
    }

    private static AdminRecord stored(AdminRecordKey key, JsonObject body) {
        return body == null ? null : new AdminRecord(key, AdminStamp.versionOf(body), body);
    }

    private interface Listing {
        List<String> names() throws IOException;
    }

    private static List<String> listed(String dbName, String folder, Listing listing) {
        try {
            return listing.names();
        } catch (IOException unlistable) {
            logger.warning("Leaving the " + folder + " of " + dbName + " out of the admin records: it could not be"
                    + " listed: " + unlistable.getMessage());
            return List.of();
        }
    }
}
