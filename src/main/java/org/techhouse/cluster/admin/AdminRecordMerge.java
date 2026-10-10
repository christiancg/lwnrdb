package org.techhouse.cluster.admin;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminTombstone;
import org.techhouse.ops.admin.GrantPruner;

public final class AdminRecordMerge {
    private static final Logger logger = Logger.logFor(AdminRecordMerge.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final Comparator<AdminRecord> APPLY_ORDER = Comparator
            .comparingInt(record -> record.key().kind().ordinal());

    public record Result(boolean complete, boolean changed) {
    }

    private enum Parent {
        LIVE, DEAD, UNKNOWN
    }

    private AdminRecordMerge() {
    }

    public static Result apply(List<AdminRecord> records, boolean live) {
        final Map<String, Long> tombstones;
        try {
            tombstones = AdminTombstone.all();
        } catch (IOException unreadable) {
            logger.warning("Merging no admin records this round: the admin tombstones could not be read: "
                    + unreadable.getMessage());
            return new Result(false, false);
        }
        var complete = true;
        var changed = false;
        for (final var record : records.stream().sorted(APPLY_ORDER).toList()) {
            try {
                hybridClock.observe(record.version());
                final var local = AdminRecords.current(record.key(), tombstones);
                if (!record.outranks(local)) {
                    continue;
                }
                final var applied = record.isTombstone()
                        ? remove(record, live, tombstones)
                        : install(record, live, tombstones);
                complete &= applied;
                changed |= applied;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(false, changed);
            } catch (Exception failure) {
                complete = false;
                logger.warning("Could not apply the admin record " + record.key().id() + ": " + failure.getMessage());
            }
        }
        return new Result(complete, changed);
    }

    private static boolean install(AdminRecord record, boolean live, Map<String, Long> tombstones) throws Exception {
        final var key = record.key();
        final var parent = parentOf(key, record.version(), tombstones);
        if (parent != Parent.LIVE) {
            return parent == Parent.DEAD;
        }
        return switch (key.kind()) {
            case DATABASE -> DatabaseRecordApplier.install(record, live, tombstones);
            case COLLECTION -> CollectionRecordApplier.install(record, live, tombstones);
            case USER -> installUser(record);
            case SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE -> DefinitionRecordApplier.install(record);
        };
    }

    private static boolean installUser(AdminRecord record) throws IOException, InterruptedException {
        final var user = AdminUserEntry.fromJsonObject(record.body());
        user.setVersion(record.version());
        AdminOperationHelper.saveUserEntry(user);
        return true;
    }

    private static boolean remove(AdminRecord record, boolean live, Map<String, Long> tombstones) throws Exception {
        final var key = record.key();
        final var removed = switch (key.kind()) {
            case DATABASE -> DatabaseRecordApplier.remove(key.dbName(), live);
            case COLLECTION -> CollectionRecordApplier.remove(key.dbName(), key.name(), live);
            case USER -> removeUser(key.name());
            case SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE -> DefinitionRecordApplier.remove(key);
        };
        if (removed) {
            AdminTombstone.recordAt(key, record.version());
            tombstones.put(key.id(), record.version());
        }
        return removed;
    }

    private static boolean removeUser(String username) throws IOException, InterruptedException {
        if (cache.getAdminUserEntry(username) != null) {
            AdminOperationHelper.deleteUserEntry(username);
            GrantPruner.forDeletedUser(username);
        }
        return true;
    }

    private static Parent parentOf(AdminRecordKey key, long version, Map<String, Long> tombstones) {
        return switch (key.kind()) {
            case DATABASE, USER -> Parent.LIVE;
            case COLLECTION, PROCEDURE, SCHEDULE -> database(key.dbName(), version, tombstones);
            case SCHEMA, TRIGGERS -> collection(key, version, tombstones);
        };
    }

    private static Parent database(String dbName, long version, Map<String, Long> tombstones) {
        if (diedAtOrAfter(AdminRecordKey.database(dbName), version, tombstones)) {
            return Parent.DEAD;
        }
        return cache.getAdminDbEntry(dbName) != null ? Parent.LIVE : Parent.UNKNOWN;
    }

    private static Parent collection(AdminRecordKey key, long version, Map<String, Long> tombstones) {
        final var database = database(key.dbName(), version, tombstones);
        if (database != Parent.LIVE) {
            return database;
        }
        if (diedAtOrAfter(AdminRecordKey.collection(key.dbName(), key.name()), version, tombstones)) {
            return Parent.DEAD;
        }
        return cache.getAdminCollectionEntry(key.dbName(), key.name()) != null ? Parent.LIVE : Parent.UNKNOWN;
    }

    private static boolean diedAtOrAfter(AdminRecordKey parent, long version, Map<String, Long> tombstones) {
        final var died = tombstones.get(parent.id());
        return died != null && died >= version;
    }
}
