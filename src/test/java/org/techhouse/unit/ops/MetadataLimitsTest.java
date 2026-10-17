package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MetadataLimitsTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        restoreDefault(ConfigKey.MAX_COLLECTIONS_PER_DATABASE);
        restoreDefault(ConfigKey.MAX_DATABASES);
        restoreDefault(ConfigKey.MAX_INDEXES_PER_COLLECTION);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void setLimit(ConfigKey key, int value) throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), key.key(), Integer.toString(value));
    }

    private static void restoreDefault(ConfigKey key) throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), key.key(), key.defaultValue());
    }

    @Test
    public void test_collection_count_is_capped_and_re_creation_still_allowed() throws Exception {
        setLimit(ConfigKey.MAX_COLLECTIONS_PER_DATABASE, 2);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "capcoll")).getStatus());
        final var refused = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "capcoll2"));
        assertEquals(ErrorCode.TOO_MANY_COLLECTIONS_FOR_DATABASE.getCode(), refused.getErrorCode());

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
    }

    @Test
    public void test_database_count_is_capped() throws Exception {
        setLimit(ConfigKey.MAX_DATABASES, 2);

        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest("capdb_two")).getStatus());
        final var refused = processor.processMessage(new CreateDatabaseRequest("capdb_three"));
        assertEquals(ErrorCode.TOO_MANY_DATABASES.getCode(), refused.getErrorCode());
    }

    @Test
    public void test_index_count_is_capped_and_re_creation_still_allowed() throws Exception {
        setLimit(ConfigKey.MAX_INDEXES_PER_COLLECTION, 1);

        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "first")).getStatus());
        final var refused = processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "second"));
        assertEquals(ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION.getCode(), refused.getErrorCode());

        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "first")).getStatus());
    }
}
