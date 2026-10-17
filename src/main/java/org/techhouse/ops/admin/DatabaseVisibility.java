package org.techhouse.ops.admin;

import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.ScriptPermissionLevel;

public final class DatabaseVisibility {

    private DatabaseVisibility() {
    }

    public static boolean isVisible(AdminUserEntry user, AdminDbEntry database) {
        if (user == null || database == null) {
            return false;
        }
        final var name = database.get_id();
        return user.isAdmin() || database.isOwner(user.get_id()) || user.getDatabasePermissions().containsKey(name)
                || hasCollectionGrant(user, name) || hasScriptGrant(user, name);
    }

    private static boolean hasCollectionGrant(AdminUserEntry user, String databaseName) {
        final var prefix = databaseName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return user.getCollectionPermissions().keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    private static boolean hasScriptGrant(AdminUserEntry user, String databaseName) {
        final var level = user.getScriptPermissions().get(databaseName);
        return level != null && level != ScriptPermissionLevel.NONE;
    }
}
