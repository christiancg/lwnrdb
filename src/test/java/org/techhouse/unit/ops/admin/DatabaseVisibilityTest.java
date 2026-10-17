package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ops.admin.DatabaseVisibility;

public class DatabaseVisibilityTest {
    private static final AdminDbEntry DB = new AdminDbEntry("db", new ArrayList<>(), new ArrayList<>());

    private static AdminUserEntry user(boolean admin, Map<String, PermissionLevel> databases,
            Map<String, PermissionLevel> collections, Map<String, ScriptPermissionLevel> scripts) {
        return new AdminUserEntry("viewer", "hash", admin, Set.of(), new HashMap<>(databases),
                new HashMap<>(collections), new HashMap<>(scripts));
    }

    private static AdminUserEntry noGrants() {
        return user(false, Map.of(), Map.of(), Map.of());
    }

    @Test
    public void admin_sees_all() {
        assertTrue(DatabaseVisibility.isVisible(user(true, Map.of(), Map.of(), Map.of()), DB));
    }

    @Test
    public void owner_sees_owned_database() {
        final var owned = new AdminDbEntry("db", new ArrayList<>(), new ArrayList<>(List.of("viewer")));
        assertTrue(DatabaseVisibility.isVisible(noGrants(), owned));
    }

    @Test
    public void database_permission_lists_it() {
        assertTrue(
                DatabaseVisibility.isVisible(user(false, Map.of("db", PermissionLevel.READ), Map.of(), Map.of()), DB));
    }

    @Test
    public void collection_permission_lists_only_its_database() {
        final var viewer = user(false, Map.of(), Map.of("db|coll", PermissionLevel.READ), Map.of());
        assertTrue(DatabaseVisibility.isVisible(viewer, DB));
        assertFalse(DatabaseVisibility.isVisible(viewer, new AdminDbEntry("other")));
    }

    @Test
    public void script_permission_run_or_manage_lists_it_but_none_does_not() {
        assertTrue(DatabaseVisibility
                .isVisible(user(false, Map.of(), Map.of(), Map.of("db", ScriptPermissionLevel.RUN)), DB));
        assertTrue(DatabaseVisibility
                .isVisible(user(false, Map.of(), Map.of(), Map.of("db", ScriptPermissionLevel.MANAGE)), DB));
        assertFalse(DatabaseVisibility
                .isVisible(user(false, Map.of(), Map.of(), Map.of("db", ScriptPermissionLevel.NONE)), DB));
    }

    @Test
    public void no_grant_lists_nothing() {
        assertFalse(DatabaseVisibility.isVisible(noGrants(), DB));
    }

    @Test
    public void collection_key_prefix_does_not_match_a_database_with_the_same_leading_characters() {
        final var viewer = user(false, Map.of("db2", PermissionLevel.READ), Map.of("db2|c", PermissionLevel.READ),
                Map.of("db2", ScriptPermissionLevel.RUN));
        assertFalse(DatabaseVisibility.isVisible(viewer, DB));
        assertTrue(DatabaseVisibility.isVisible(viewer, new AdminDbEntry("db2")));
    }

    @Test
    public void null_user_sees_nothing() {
        assertFalse(DatabaseVisibility.isVisible(null, DB));
        assertFalse(DatabaseVisibility.isVisible(noGrants(), null));
    }
}
