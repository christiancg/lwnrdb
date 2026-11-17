package org.techhouse.cluster.admin;

import java.util.Objects;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.GrantPruner;
import org.techhouse.ops.admin.GrantRewrite;

final class UserRecordApplier {
    private static final Logger logger = Logger.logFor(UserRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    private UserRecordApplier() {
    }

    private interface UserChange {
        void apply() throws Exception;
    }

    static boolean install(AdminRecord record) throws Exception {
        return underUsersLock(record, () -> {
            if (!AdminRecords.stillOutranks(record)) {
                return;
            }
            final var user = AdminUserEntry.fromJsonObject(record.body());
            user.setVersion(record.version());
            final var previous = cache.getAdminUserEntry(user.get_id());
            AdminOperationHelper.saveUserEntry(user);
            if (previous != null && !Objects.equals(previous.getPasswordHash(), user.getPasswordHash())) {
                clientTracker.deauthenticateUser(user.get_id());
            }
        });
    }

    static boolean remove(AdminRecord tombstone) throws Exception {
        final var key = tombstone.key();
        return underUsersLock(tombstone, () -> {
            AdminStamp.tombstoneAt(key, tombstone.version());
            if (cache.getAdminUserEntry(key.name()) == null || !tombstone.outranks(AdminRecords.live(key))) {
                return;
            }
            AdminOperationHelper.deleteUserEntry(key.name());
            GrantPruner.forDeletedUser(key.name(), GrantRewrite.KEEP);
        });
    }

    private static boolean underUsersLock(AdminRecord record, UserChange change) throws Exception {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, waitMillis)) {
            logger.warning("Skipping " + record.key().id() + ": the users lock stayed held for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            change.apply();
            return true;
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }
}
