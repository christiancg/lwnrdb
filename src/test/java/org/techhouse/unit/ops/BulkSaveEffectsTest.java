package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.BulkSaveEffects;
import org.techhouse.ops.ClusterWriteHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BulkSaveEffectsTest {
    private static final String ACTING_USER = "bulk-writer";
    private static final int DEPTH = 2;
    private static final List<String> COMMITTED_INSERTS = List.of("inserted");
    private static final List<String> COMMITTED_UPDATES = List.of("updated");

    private final BulkSaveResponse committed = new BulkSaveResponse("Partially saved entries", COMMITTED_INSERTS,
            COMMITTED_UPDATES);

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

    private static BulkSaveRequest bulkRequest() {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, "inserted");
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(object));
        request.setTriggerDepth(DEPTH);
        return request;
    }

    private PartialBulkSaveException partialFailure() {
        return new PartialBulkSaveException(committed, new IOException("disk full"));
    }

    private static boolean isTheCommittedPart(OperationResponse response) {
        return response instanceof BulkSaveResponse bulk && bulk.getInserted().equals(COMMITTED_INSERTS)
                && bulk.getUpdated().equals(COMMITTED_UPDATES);
    }

    @Test
    public void test_triggers_fire_for_the_committed_part_of_a_failed_bulk_save() {
        final var request = bulkRequest();
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var triggers = mockStatic(TriggerHelper.class);
                var ignored = mockStatic(ClusterWriteHelper.class)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(request)).thenThrow(partialFailure());

            assertThrows(PartialBulkSaveException.class, () -> BulkSaveEffects.executeAndPublish(request, ACTING_USER));

            triggers.verify(() -> TriggerHelper.afterBulkSave(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    argThat(BulkSaveEffectsTest::isTheCommittedPart), eq(ACTING_USER), eq(DEPTH)));
        }
    }

    @Test
    public void test_the_committed_part_is_replicated() {
        final var request = bulkRequest();
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var ignored = mockStatic(TriggerHelper.class);
                var cluster = mockStatic(ClusterWriteHelper.class)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(request)).thenThrow(partialFailure());

            assertThrows(PartialBulkSaveException.class, () -> BulkSaveEffects.executeAndPublish(request, ACTING_USER));

            cluster.verify(() -> ClusterWriteHelper.afterBulkSave(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    argThat(BulkSaveEffectsTest::isTheCommittedPart)));
        }
    }

    @Test
    public void test_success_path_is_unchanged() throws Exception {
        final var request = bulkRequest();
        final var replicated = new OperationResponse(null, ErrorCode.ERROR_BULK_SAVING);
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var triggers = mockStatic(TriggerHelper.class);
                var cluster = mockStatic(ClusterWriteHelper.class)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(request)).thenReturn(committed);
            cluster.when(() -> ClusterWriteHelper.afterBulkSave(TestGlobals.DB, TestGlobals.COLL, committed))
                    .thenReturn(replicated);

            assertSame(replicated, BulkSaveEffects.executeAndPublish(request, ACTING_USER),
                    "a successful bulk save answers with whatever replication made of it");
            triggers.verify(
                    () -> TriggerHelper.afterBulkSave(TestGlobals.DB, TestGlobals.COLL, committed, ACTING_USER, DEPTH));
        }
    }

    @Test
    public void test_the_client_still_receives_error_bulk_saving() {
        final var request = bulkRequest();
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var triggers = mockStatic(TriggerHelper.class, CALLS_REAL_METHODS)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(any(BulkSaveRequest.class)))
                    .thenThrow(partialFailure());

            final var response = IocContainer.get(OperationProcessor.class).processMessage(request);

            assertEquals(ErrorCode.ERROR_BULK_SAVING.getCode(), response.getErrorCode(),
                    "publishing the committed part must not turn the failed request into a success");
            triggers.verify(() -> TriggerHelper.afterBulkSave(anyString(), anyString(),
                    argThat(BulkSaveEffectsTest::isTheCommittedPart), any(), anyInt()));
        }
    }
}
