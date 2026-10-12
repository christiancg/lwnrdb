package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.test.TestUtils;

public class DatabaseOperationHelperListTest {

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("granted_db"));
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("hidden_db"));
        AdminOperationHelper
                .saveDatabaseEntry(new AdminDbEntry("owned_db", new ArrayList<>(), new ArrayList<>(List.of("lister"))));
        AdminOperationHelper.saveUserEntry(new AdminUserEntry("lister", "hash", false, new HashSet<>(),
                new HashMap<>(Map.of("granted_db", PermissionLevel.READ)), new HashMap<>()));
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry("boss", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<String> listedFor(String username) {
        final var response = DatabaseOperationHelper.processListDatabasesOperation(username);
        return assertInstanceOf(ListDatabasesResponse.class, response).getDatabases();
    }

    @Test
    public void list_databases_is_filtered_for_the_acting_user() {
        assertEquals(List.of("granted_db", "owned_db"), listedFor("lister"));
    }

    @Test
    public void admin_lists_every_user_database_sorted() {
        assertEquals(List.of("granted_db", "hidden_db", "owned_db"), listedFor("boss"));
    }

    @Test
    public void an_unresolvable_user_lists_nothing() {
        assertEquals(List.of(), listedFor(null));
        assertEquals(List.of(), listedFor("deleted_user"));
    }
}
