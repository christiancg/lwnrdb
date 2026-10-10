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

public final class GrantPruner {
    private static final Logger logger = Logger.logFor(GrantPruner.class);

    private GrantPruner() {
    }

    public static void forDroppedDatabase(String dbName) {
        final var collectionPrefix = dbName + "|";
        pruneGrants(dbName,
                user -> withoutGrants(user, dbName::equals, key -> key.startsWith(collectionPrefix), dbName::equals));
    }

    public static void forDroppedCollection(String dbName, String collName) {
        final var collectionKey = dbName + "|" + collName;
        pruneGrants(dbName + "|" + collName,
                user -> withoutGrants(user, _ -> false, collectionKey::equals, _ -> false));
    }

    public static void forDeletedUser(String username) {
        try {
            AdminOperationHelper.rewriteDatabases(database -> withoutOwner(database, username));
        } catch (Exception failure) {
            logger.error("Could not remove deleted user '" + username + "' from the database owners; a user "
                    + "created later under that name would inherit them", failure);
        }
    }

    private static void pruneGrants(String target, UnaryOperator<AdminUserEntry> prune) {
        try {
            AdminOperationHelper.rewriteUsers(prune);
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
