package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DatabaseStatsHelper;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.test.TestUtils;

public class DatabaseStatsFilterTest {
    private static final List<String> NODE_SECTIONS = List.of("memory", "inDoubtTransactions", "triggers", "schedules",
            "scripts");

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        AdminOperationHelper.saveDatabaseEntry(
                new AdminDbEntry("stats_shop", new ArrayList<>(), new ArrayList<>(List.of("stats_owner"))));
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("stats_crm"));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_shop", "orders", Set.of("customer")));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_shop", "items"));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_crm", "leads", Set.of("score", "region")));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_crm", "notes"));
        saveUser("stats_admin", true, Map.of(), Map.of(), Map.of());
        saveUser("stats_owner", false, Map.of(), Map.of(), Map.of());
        saveUser("stats_reader", false, Map.of("stats_crm", PermissionLevel.READ), Map.of(), Map.of());
        saveUser("stats_collgrant", false, Map.of(), Map.of("stats_crm|leads", PermissionLevel.READ), Map.of());
        saveUser("stats_scripter", false, Map.of(), Map.of(), Map.of("stats_crm", ScriptPermissionLevel.MANAGE));
        saveUser("stats_nobody", false, Map.of(), Map.of(), Map.of());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveUser(String name, boolean admin, Map<String, PermissionLevel> databases,
            Map<String, PermissionLevel> collections, Map<String, ScriptPermissionLevel> scripts) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, "hash", admin, new HashSet<>(),
                new HashMap<>(databases), new HashMap<>(collections), new HashMap<>(scripts)));
    }

    private static JsonObject statsFor(String username) {
        return assertInstanceOf(GetDatabaseStatsResponse.class, DatabaseStatsHelper.processGetDatabaseStats(username))
                .getStats();
    }

    private static Map<String, List<String>> collectionsByDatabase(JsonObject stats) {
        final var found = new LinkedHashMap<String, List<String>>();
        for (final var database : stats.get("databases").asJsonArray()) {
            final var names = new ArrayList<String>();
            for (final var collection : database.asJsonObject().get("collections").asJsonArray()) {
                names.add(collection.asJsonObject().get("name").asJsonString().getValue());
            }
            found.put(database.asJsonObject().get("name").asJsonString().getValue(), names);
        }
        return found;
    }

    private static void assertNoNodeSections(JsonObject stats) {
        for (final var section : NODE_SECTIONS) {
            assertFalse(stats.has(section), section);
        }
        assertFalse(stats.get("totals").asJsonObject().has("userCount"));
    }

    private static long total(JsonObject stats, String field) {
        return stats.get("totals").asJsonObject().get(field).asJsonNumber().getValue().longValue();
    }

    @Test
    public void test_admin_gets_the_full_report() {
        final var stats = statsFor("stats_admin");

        for (final var section : NODE_SECTIONS) {
            assertTrue(stats.has(section), section);
        }
        assertTrue(stats.get("totals").asJsonObject().has("userCount"));
        assertEquals(Map.of("stats_shop", List.of("items", "orders"), "stats_crm", List.of("leads", "notes")),
                collectionsByDatabase(stats));
    }

    @Test
    public void test_owner_gets_their_database_with_every_collection_and_no_node_sections() {
        final var stats = statsFor("stats_owner");

        assertEquals(Map.of("stats_shop", List.of("items", "orders")), collectionsByDatabase(stats));
        assertNoNodeSections(stats);
    }

    @Test
    public void test_database_read_grant_sees_that_database() {
        assertEquals(Map.of("stats_crm", List.of("leads", "notes")), collectionsByDatabase(statsFor("stats_reader")));
    }

    @Test
    public void test_collection_only_grant_sees_only_that_collection() {
        assertEquals(Map.of("stats_crm", List.of("leads")), collectionsByDatabase(statsFor("stats_collgrant")));
    }

    @Test
    public void test_script_only_grant_sees_the_database_with_no_collections() {
        assertEquals(Map.of("stats_crm", List.of()), collectionsByDatabase(statsFor("stats_scripter")));
    }

    @Test
    public void test_totals_cover_only_the_visible_view() {
        final var stats = statsFor("stats_collgrant");

        assertEquals(1L, total(stats, "databaseCount"));
        assertEquals(1L, total(stats, "collectionCount"));
        assertEquals(2L, total(stats, "indexCount"));
        assertNoNodeSections(stats);
        final var crm = stats.get("databases").asJsonArray().get(0).asJsonObject();
        assertEquals(1L, crm.get("collectionCount").asJsonNumber().getValue().longValue());
    }

    @Test
    public void test_no_grant_sees_no_databases() {
        final var stats = statsFor("stats_nobody");

        assertEquals(Map.of(), collectionsByDatabase(stats));
        assertEquals(0L, total(stats, "databaseCount"));
    }

    @Test
    public void test_unknown_or_null_user_gets_an_empty_view() {
        for (final var stats : List.of(statsFor(null), statsFor("deleted_user"))) {
            assertEquals(Map.of(), collectionsByDatabase(stats));
            assertNoNodeSections(stats);
        }
    }
}
