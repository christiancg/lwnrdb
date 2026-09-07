package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.ListTriggersResponse;
import org.techhouse.ops.resp.SaveTriggerResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * SAVE_TRIGGER and DELETE_TRIGGER as dispatched by the processor: both take the collection write lock before
 * rewriting the trigger file, so that they serialize against a concurrent save to the same collection.
 */
public class OperationProcessorTriggerDispatchTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        processor.processMessage(new SaveProcedureRequest(TestGlobals.DB, "audit", "export default () => 1;"));
    }

    private SaveTriggerRequest saveRequest() {
        return new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "onInsert", List.of("CREATED"), "audit");
    }

    @Test
    public void test_dispatches_save_list_and_delete() {
        final var saved = processor.processMessage(saveRequest());
        assertInstanceOf(SaveTriggerResponse.class, saved, saved.getMessage());

        final var listed = (ListTriggersResponse) processor
                .processMessage(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(1, listed.getTriggers().size());
        assertEquals("onInsert", listed.getTriggers().getFirst().get("name").asJsonString().getValue());

        assertEquals(OperationStatus.OK, processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "onInsert")).getStatus());
        final var afterDelete = (ListTriggersResponse) processor
                .processMessage(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL));
        assertTrue(afterDelete.getTriggers().isEmpty());
    }

    // Listing without a collection reports every trigger in the database, tagged with its collection
    @Test
    public void test_listing_the_whole_database_tags_each_trigger_with_its_collection() {
        processor.processMessage(saveRequest());
        final var listed = (ListTriggersResponse) processor
                .processMessage(new ListTriggersRequest(TestGlobals.DB, null));
        assertEquals(1, listed.getTriggers().size());
        assertEquals(TestGlobals.COLL, listed.getTriggers().getFirst().get("collectionName").asJsonString().getValue());
    }

    @Test
    public void test_saving_a_trigger_on_an_unknown_collection_is_refused() {
        final var request = new SaveTriggerRequest(TestGlobals.DB, "noSuchColl", "onInsert", List.of("CREATED"),
                "audit");
        final var response = processor.processMessage(request);
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_deleting_a_trigger_on_an_unknown_collection_is_refused() {
        final var response = processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.DB, "noSuchColl", "onInsert"));
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    // A trigger pointing at a procedure that does not exist fails the save rather than the first write
    @Test
    public void test_saving_a_trigger_for_an_unknown_procedure_is_refused() {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "onInsert", List.of("CREATED"),
                "missing");
        final var response = processor.processMessage(request);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_deleting_a_trigger_that_is_not_there_is_idempotent() {
        assertEquals(OperationStatus.OK, processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "absent")).getStatus());
    }
}
