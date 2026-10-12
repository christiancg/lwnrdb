package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.AdminOperationHelper.RewriteVersion;

public final class GrantPruner {
    private static final Logger logger = Logger.logFor(GrantPruner.class);

    private GrantPruner() {
    }

    public static void forDroppedDatabase(String dbName, RewriteVersion version) {
        final var collectionPrefix = dbName + "|";
        pruneGrants(dbName, version,
                user -> withoutGrants(user, dbName::equals, key -> key.startsWith(collectionPrefix), dbName::equals));
    }

    public static void forDroppedCollection(String dbName, String collName, RewriteVersion version) {
        final var collectionKey = dbName + "|" + collName;
        pruneGrants(dbName + "|" + collName, version,
                user -> withoutGrants(user, _ -> false, collectionKey::equals, _ -> false));
    }

    public static void forDeletedUser(String username, RewriteVersion version) {
        try {
            final var rewritten = AdminOperationHelper.rewriteDatabases(database -> withoutOwner(database, username),
                    version);
            if (version == RewriteVersion.RESTAMP) {
                rewritten.forEach(dbName -> RestampedRecords.add(AdminRecordKey.database(dbName)));
            }
        } catch (Exception failure) {
            logger.error("Could not remove deleted user '" + username + "' from the database owners; a user "
                    + "created later under that name would inherit them", failure);
        }
    }

    private static void pruneGrants(String target, RewriteVersion version, UnaryOperator<AdminUserEntry> prune) {
        try {
            final var rewritten = AdminOperationHelper.rewriteUsers(prune, version);
            if (version == RewriteVersion.RESTAMP) {
                rewritten.forEach(username -> RestampedRecords.add(AdminRecordKey.user(username)));
            }
        } catch (Exception failure) {
            logger.error("Could not remove the permission grants naming '" + target + "'; a database or collection "
                    + "created later under that name would inherit them", failure);
        }
    }

    private static AdminDbEntry withoutOwner(AdminDbEntry database, String username) {
        if (!database.isOwner(username)) {
            return null;
        }
        final var owners = new ArrayList<>(database.getOwners());
        owners.removeIf(username::equals);
        return new AdminDbEntry(database.get_id(), new ArrayList<>(database.getCollections()), owners);
    }

    private static AdminUserEntry withoutGrants(AdminUserEntry user, Predicate<String> databaseKey,
            Predicate<String> collectionKey, Predicate<String> scriptKey) {
        final var databases = new HashMap<>(user.getDatabasePermissions());
        final var collections = new HashMap<>(user.getCollectionPermissions());
        final var scripts = new HashMap<>(user.getScriptPermissions());
        final var removed = removeKeys(databases, databaseKey) | removeKeys(collections, collectionKey)
                | removeKeys(scripts, scriptKey);
        if (!removed) {
            return null;
        }
        return new AdminUserEntry(user.get_id(), user.getPasswordHash(), user.isAdmin(), user.getGlobalPermissions(),
                databases, collections, scripts);
    }

    private static boolean removeKeys(Map<String, ?> grants, Predicate<String> matches) {
        return grants.keySet().removeIf(matches);
    }
}
