package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ops.CollectionReadinessGuard;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CollectionReadinessGuardTest {
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        origEnabled = config.isClusterEnabled();
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_known_collection_is_allowed_through() {
        assertNull(CollectionReadinessGuard.check(OperationType.SAVE, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_an_unknown_collection_is_a_definitive_not_found_without_clustering() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var response = CollectionReadinessGuard.check(OperationType.SAVE, TestGlobals.DB, "missing");
        assertNotNull(response);
        assertEquals("404-11", response.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
    }

    @Test
    public void test_an_unknown_collection_is_retryable_when_clustered() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        final var response = CollectionReadinessGuard.check(OperationType.BULK_SAVE, TestGlobals.DB, "missing");
        assertNotNull(response);
        assertEquals("503-10", response.getErrorCode());
        assertEquals(OperationStatus.ERROR, response.getStatus());
    }

    @Test
    public void test_the_refusal_carries_the_requested_operation_type() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var response = CollectionReadinessGuard.check(OperationType.DELETE, TestGlobals.DB, "missing");
        assertNotNull(response);
        assertEquals(OperationType.DELETE, response.getType());
    }

    @Test
    public void test_an_unknown_database_is_refused_too() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var response = CollectionReadinessGuard.check(OperationType.SAVE, "no-such-db", TestGlobals.COLL);
        assertNotNull(response);
        assertEquals("404-11", response.getErrorCode());
    }

    @Test
    public void test_read_of_registered_collection_passes() {
        assertNull(
                CollectionReadinessGuard.checkRead(OperationType.AGGREGATE, TestGlobals.DB, List.of(TestGlobals.COLL)));
    }

    @Test
    public void test_read_of_unregistered_collection_is_not_found() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var response = CollectionReadinessGuard.checkRead(OperationType.FIND_BY_ID, TestGlobals.DB,
                List.of("missing"));
        assertNotNull(response);
        assertEquals("404-11", response.getErrorCode());
        assertEquals(OperationType.FIND_BY_ID, response.getType());
    }

    @Test
    public void test_clustered_read_of_unregistered_collection_is_not_ready() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        final var response = CollectionReadinessGuard.checkRead(OperationType.LISTEN, TestGlobals.DB,
                List.of("missing"));
        assertNotNull(response);
        assertEquals("503-10", response.getErrorCode());
    }

    @Test
    public void test_join_target_unregistered_is_refused() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var response = CollectionReadinessGuard.checkRead(OperationType.AGGREGATE, TestGlobals.DB,
                List.of(TestGlobals.COLL, "missing"));
        assertNotNull(response);
        assertEquals("404-11", response.getErrorCode());
    }

    @Test
    public void test_mis_cased_collection_read_is_refused() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var response = CollectionReadinessGuard.checkRead(OperationType.FIND_BY_ID, TestGlobals.DB,
                List.of(TestGlobals.COLL.toUpperCase(Locale.ROOT)));
        assertNotNull(response, "a spelling that differs only in case names no registered collection");
        assertEquals("404-11", response.getErrorCode());
    }

    @Test
    public void test_admin_database_read_passes() {
        assertNull(CollectionReadinessGuard.checkRead(OperationType.AGGREGATE, Globals.ADMIN_DB_NAME,
                List.of(Globals.ADMIN_USERS_COLLECTION_NAME)));
        assertNull(CollectionReadinessGuard.checkRead(OperationType.AGGREGATE, Globals.ADMIN_PAGES_DB_NAME,
                List.of("anything")));
    }

    @Test
    public void test_mis_cased_admin_database_read_is_refused() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        assertNotNull(CollectionReadinessGuard.checkRead(OperationType.AGGREGATE,
                Globals.ADMIN_DB_NAME.toUpperCase(Locale.ROOT), List.of(Globals.ADMIN_USERS_COLLECTION_NAME)));
    }

    @Test
    public void test_unwritten_script_runs_read_passes() {
        assertNull(CollectionReadinessGuard.checkRead(OperationType.AGGREGATE, TestGlobals.DB,
                List.of(Globals.SCRIPT_RUNS_COLLECTION_NAME)));
    }

    @Test
    public void test_mis_cased_script_runs_read_is_refused() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        assertNotNull(CollectionReadinessGuard.checkRead(OperationType.AGGREGATE, TestGlobals.DB,
                List.of(Globals.SCRIPT_RUNS_COLLECTION_NAME.toUpperCase(Locale.ROOT))));
    }
}
