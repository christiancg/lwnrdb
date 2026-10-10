package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.index.IndexOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexOperationHelperReadinessTest {
    private static final String ABSENT_COLL = "never_created";
    private static final String FIELD = "email";
    private final Configuration config = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
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
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static CreateIndexRequest createIndexOn(String collName) {
        return new CreateIndexRequest(TestGlobals.DB, collName, FIELD);
    }

    private static File absentCollectionFolder() {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + ABSENT_COLL);
    }

    @Test
    public void create_index_on_missing_collection_answers_not_found() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);

        final var response = processor.processMessage(createIndexOn(ABSENT_COLL));

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void create_index_on_missing_collection_answers_not_ready_when_clustered() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);

        final var response = IndexOperationHelper.processCreateIndex(createIndexOn(ABSENT_COLL));

        assertEquals(ErrorCode.COLLECTION_NOT_READY.getCode(), response.getErrorCode());
    }

    @Test
    public void create_index_on_missing_collection_leaves_nothing_behind() {
        processor.processMessage(createIndexOn(ABSENT_COLL));

        assertFalse(absentCollectionFolder().exists());
        final var created = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, ABSENT_COLL));
        assertEquals(OperationStatus.OK, created.getStatus());
        assertTrue(cache.hasNoIndex(TestGlobals.DB, ABSENT_COLL, FIELD));
    }

    @Test
    public void create_index_on_an_existing_collection_still_succeeds() {
        final var response = processor.processMessage(createIndexOn(TestGlobals.COLL));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(cache.hasNoIndex(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }
}
