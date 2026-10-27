package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.PageOccupancyReconciler;

final class CommitWriteGroups {
    static final CommitWriteGroups NONE = new CommitWriteGroups(Set.of());
    private static final int MIN_GROUPED_OPS = 2;
    private static final Logger logger = Logger.logFor(CommitWriteGroups.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    private record Target(String dbName, String collName) {
    }

    private final Set<String> eligible;
    private final Map<String, Target> opened = new LinkedHashMap<>();

    private CommitWriteGroups(Set<String> eligible) {
        this.eligible = eligible;
    }

    static CommitWriteGroups plan(List<AdminTransactionEntry> ops) {
        if (!configuration.isSyncWrites()) {
            return NONE;
        }
        final var counts = new HashMap<String, Integer>();
        for (final var op : ops) {
            if (writesData(op)) {
                counts.merge(Cache.getCollectionIdentifier(op.getTargetDb(), op.getTargetColl()), 1, Integer::sum);
            }
        }
        final var eligible = new HashSet<String>();
        counts.forEach((collId, count) -> {
            if (count >= MIN_GROUPED_OPS) {
                eligible.add(collId);
            }
        });
        return eligible.isEmpty() ? NONE : new CommitWriteGroups(eligible);
    }

    static boolean writesData(AdminTransactionEntry op) {
        return switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE, AdminTransactionEntry.OP_TYPE_BULK_SAVE,
                    AdminTransactionEntry.OP_TYPE_DELETE ->
                true;
            default -> false;
        };
    }

    void ensureOpen(String dbName, String collName) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        if (!eligible.contains(collId)) {
            return;
        }
        try {
            fs.openWriteGroup(dbName, collName);
            opened.putIfAbsent(collId, new Target(dbName, collName));
        } catch (IOException e) {
            logger.warning("Could not open a write group on " + collId + " for a commit; its writes are forced one"
                    + " by one: " + e.getMessage());
        }
    }

    boolean settle() {
        var durable = true;
        for (final var target : opened.values()) {
            if (!settleOne(target)) {
                durable = false;
                forgetTakenBack(target);
            }
        }
        opened.clear();
        return durable;
    }

    private static boolean settleOne(Target target) {
        try {
            return fs.settleWriteGroup(target.dbName(), target.collName());
        } catch (IOException e) {
            logger.error("Could not settle the write group of a commit on "
                    + Cache.getCollectionIdentifier(target.dbName(), target.collName()), e);
            return false;
        }
    }

    private static void forgetTakenBack(Target target) {
        cache.evictCollection(target.dbName(), target.collName());
        try {
            PageOccupancyReconciler.reconcile(target.dbName(), target.collName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("Could not reconcile the page rows of "
                    + Cache.getCollectionIdentifier(target.dbName(), target.collName())
                    + " after a commit's write group failed; the next startup rebuilds them: " + e.getMessage());
        }
    }
}
