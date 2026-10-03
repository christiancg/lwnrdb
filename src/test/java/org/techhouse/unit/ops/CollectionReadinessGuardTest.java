package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
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
}
