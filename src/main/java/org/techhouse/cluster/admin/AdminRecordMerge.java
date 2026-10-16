package org.techhouse.cluster.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.HybridClock;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.GrantPruner;
import org.techhouse.ops.admin.GrantRewrite;

public final class AdminRecordMerge {
    private static final Logger logger = Logger.logFor(AdminRecordMerge.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final Comparator<AdminRecord> APPLY_ORDER = Comparator
            .comparingInt(record -> record.key().kind().ordinal());

    public record Result(boolean complete, boolean changed) {
    }

    private enum Parent {
        LIVE, DEAD, UNKNOWN
    }

    private enum Verdict {
        ACCEPT, SKIP, DEFER
    }

    private record Screened(List<AdminRecord> accepted, boolean anyDeferred) {
    }

    private AdminRecordMerge() {
    }

    public static Result apply(List<AdminRecord> records, boolean live) {
        final Map<String, Long> tombstones;
        try {
            tombstones = AdminStamp.tombstones();
        } catch (IOException unreadable) {
            logger.warning("Merging no admin records this round: the admin tombstones could not be read: "
                    + unreadable.getMessage());
            return new Result(false, false);
        }
        final var screened = screenInApplyOrder(records);
        var complete = !screened.anyDeferred();
        var changed = false;
        for (final var record : screened.accepted()) {
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

    private static Screened screenInApplyOrder(List<AdminRecord> records) {
        final var accepted = new ArrayList<AdminRecord>();
        var anyDeferred = false;
        for (final var record : records) {
            final var verdict = verdictOn(record);
            if (verdict == Verdict.ACCEPT) {
                accepted.add(record);
            } else if (verdict == Verdict.DEFER) {
                anyDeferred = true;
            }
        }
        accepted.sort(APPLY_ORDER);
        return new Screened(accepted, anyDeferred);
    }

    private static Verdict verdictOn(AdminRecord record) {
        final var key = record == null ? null : AdminRecordNames.parse(record.rawKey());
        if (key == null) {
            logger.warning("Skipping a replicated admin record whose key is not a valid name");
            return Verdict.SKIP;
        }
        if (key.kind() == AdminRecordKey.Kind.COLLECTION && !record.isTombstone()
                && !CollectionRecordApplier.indexFieldsAreValid(record)) {
            logger.warning("Skipping a replicated collection admin record that names an invalid index field");
            return Verdict.SKIP;
        }
        final var skew = skewVerdict(record, key);
        if (skew == Verdict.SKIP) {
            logger.warning("Skipping a replicated " + key.kind() + " admin record whose version is unreadable");
        } else if (skew == Verdict.DEFER) {
            logger.warning("Deferring a replicated " + key.kind() + " admin record: its version is more than"
                    + " clusterMaxClockSkewMs ahead of this node's clock; the round stays incomplete");
        }
        return skew;
    }

    private static Verdict skewVerdict(AdminRecord record, AdminRecordKey key) {
        try {
            final var withinSkew = hybridClock.withinSkew(record.version(), clusterConfig.maxClockSkewMs())
                    && (key.kind() != AdminRecordKey.Kind.COLLECTION || record.isTombstone()
                            || CollectionRecordApplier.incarnationWithinSkew(record));
            return withinSkew ? Verdict.ACCEPT : Verdict.DEFER;
        } catch (RuntimeException unreadable) {
            return Verdict.SKIP;
        }
    }

    private static boolean install(AdminRecord record, boolean live, Map<String, Long> tombstones) throws Exception {
        final var key = record.key();
        final var parent = parentOf(key, record.version(), tombstones);
        if (parent != Parent.LIVE) {
            return parent == Parent.DEAD;
        }
        final var sibling = caseVariantOf(key);
        if (sibling != null) {
            final var siblingRecord = AdminRecords.live(sibling);
            if (siblingRecord != null && !record.outranks(siblingRecord)) {
                bury(key, siblingRecord.version(), tombstones);
                return true;
            }
            if (!remove(AdminRecord.tombstone(sibling, record.version()), live, tombstones)) {
                return false;
            }
        }
        return switch (key.kind()) {
            case DATABASE -> DatabaseRecordApplier.install(record);
            case COLLECTION -> CollectionRecordApplier.install(record);
            case USER -> installUser(record);
            case SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE -> DefinitionRecordApplier.install(record);
        };
    }

    private static AdminRecordKey caseVariantOf(AdminRecordKey key) {
        return switch (key.kind()) {
            case DATABASE -> DatabaseRecordApplier.caseVariantOf(key.dbName());
            case COLLECTION -> CollectionRecordApplier.caseVariantOf(key.dbName(), key.name());
            default -> null;
        };
    }

    private static void bury(AdminRecordKey key, long version, Map<String, Long> tombstones) throws IOException {
        AdminStamp.tombstoneAt(key, version);
        tombstones.put(key.id(), version);
    }

    private static boolean installUser(AdminRecord record) throws IOException, InterruptedException {
        final var user = AdminUserEntry.fromJsonObject(record.body());
        user.setVersion(record.version());
        final var previous = cache.getAdminUserEntry(user.get_id());
        AdminOperationHelper.saveUserEntry(user);
        if (previous != null && !Objects.equals(previous.getPasswordHash(), user.getPasswordHash())) {
            clientTracker.deauthenticateUser(user.get_id());
        }
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
            bury(key, record.version(), tombstones);
        }
        return removed;
    }

    private static boolean removeUser(String username) throws IOException, InterruptedException {
        if (cache.getAdminUserEntry(username) != null) {
            AdminOperationHelper.deleteUserEntry(username);
            GrantPruner.forDeletedUser(username, GrantRewrite.KEEP);
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
