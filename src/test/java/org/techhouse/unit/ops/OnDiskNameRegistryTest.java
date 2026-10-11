package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OnDiskNameRegistryTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void createCollection(String collName) {
        final var request = new CreateCollectionRequest(TestGlobals.DB, collName);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void registerGroupedPairBehindTheNameCheck() throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, "GroupedPair"));
    }

    @Test
    public void test_exact_name_is_not_a_collision() {
        assertNull(OnDiskNameRegistry.collidingDatabase(TestGlobals.DB));
        assertNull(OnDiskNameRegistry.collidingCollection(TestGlobals.DB, TestGlobals.COLL));
    }

    private static Stream<Arguments> collidingDatabases() {
        return Stream.of(
                Arguments.of("test_case_differing_database_collides upper", TestGlobals.DB.toUpperCase(Locale.ROOT),
                        TestGlobals.DB),
                Arguments.of("test_case_differing_database_collides lower", TestGlobals.DB.toLowerCase(Locale.ROOT),
                        TestGlobals.DB),
                Arguments.of("test_case_differing_admin_database_collides Admin", "Admin", Globals.ADMIN_DB_NAME),
                Arguments.of("test_case_differing_admin_database_collides ADMIN", "ADMIN", Globals.ADMIN_DB_NAME),
                Arguments.of("test_admin_pages_reserved_name_collides", "Admin_Pages", Globals.ADMIN_PAGES_DB_NAME),
                Arguments.of("test_case_differing_cluster_folder_collides", "Cluster", Globals.CLUSTER_FOLDER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collidingDatabases")
    public void test_a_database_name_collides_with_its_on_disk_folder(String name, String requested, String expected) {
        assertEquals(expected, OnDiskNameRegistry.collidingDatabase(requested), name);
    }

    @Test
    public void test_case_differing_collection_collides() {
        assertEquals(TestGlobals.COLL,
                OnDiskNameRegistry.collidingCollection(TestGlobals.DB, TestGlobals.COLL.toUpperCase(Locale.ROOT)));
    }

    @Test
    public void test_case_differing_index_field_collides() {
        final var request = new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "someField");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertEquals("someField",
                OnDiskNameRegistry.collidingIndexField(TestGlobals.DB, TestGlobals.COLL, "SOMEFIELD"));
        assertNull(OnDiskNameRegistry.collidingIndexField(TestGlobals.DB, TestGlobals.COLL, "someField"));
    }

    @Test
    public void test_case_differing_definition_name_collides() {
        assertEquals("MyProc", OnDiskNameRegistry.collidingDefinition(List.of("MyProc", "other"), "myproc"));
        assertNull(OnDiskNameRegistry.collidingDefinition(List.of("MyProc", "other"), "MyProc"));
    }

    @Test
    public void test_only_a_database_named_like_the_cluster_folder_is_listed_as_in_it() throws Exception {
        assertEquals(List.of(), OnDiskNameRegistry.registeredDatabasesInTheClusterFolder());
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("CLUSTER"));
        assertEquals(List.of("CLUSTER"), OnDiskNameRegistry.registeredDatabasesInTheClusterFolder());
    }

    @Test
    public void test_unrelated_names_do_not_collide() {
        assertNull(OnDiskNameRegistry.collidingDatabase("somethingElse"));
        assertNull(OnDiskNameRegistry.collidingCollection(TestGlobals.DB, "unrelatedCollection"));
        assertNull(OnDiskNameRegistry.collidingIndexField(TestGlobals.DB, TestGlobals.COLL, "noSuchField"));
    }

    @Test
    public void test_unknown_database_has_no_colliding_collection() {
        assertNull(OnDiskNameRegistry.collidingCollection("noSuchDatabase", "anything"));
    }

    @Test
    public void test_empty_registry_answers_null() {
        assertNull(OnDiskNameRegistry.collidingDefinition(List.of(), "anything"));
        assertNull(OnDiskNameRegistry.collidingDefinition(List.of("a"), null));
    }

    @Test
    public void test_on_disk_key_folds_case_only() {
        assertEquals("mycoll", OnDiskNameRegistry.onDiskKey("MyColl"));
        assertEquals("first-name", OnDiskNameRegistry.onDiskKey("First-Name"));
        assertNotEquals(OnDiskNameRegistry.onDiskKey("a_b"), OnDiskNameRegistry.onDiskKey("a-b"));
    }

    @Test
    public void test_grouped_by_on_disk_key_is_empty_when_names_are_distinct() {
        assertTrue(OnDiskNameRegistry.groupedByOnDiskKey().isEmpty());
    }

    @Test
    public void test_grouped_by_on_disk_key_reports_a_case_differing_collection_pair() throws Exception {
        createCollection("groupedPair");
        registerGroupedPairBehindTheNameCheck();
        final var groups = OnDiskNameRegistry.groupedByOnDiskKey();
        assertEquals(1, groups.size());
        assertEquals(List.of("groupedPair", "GroupedPair"), groups.getFirst());
    }

    @Test
    public void test_same_collection_name_in_two_databases_does_not_group() {
        createCollection("perDatabase");
        final var otherDb = new CreateDatabaseRequest("otherDb");
        assertEquals(OperationStatus.OK, processor.processMessage(otherDb).getStatus());
        final var request = new CreateCollectionRequest("otherDb", "perDatabase");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertTrue(OnDiskNameRegistry.groupedByOnDiskKey().isEmpty());
    }
}
