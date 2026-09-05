package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.SaveTriggerResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerOperationHelperBeforeTimingTest {
    private static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
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
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "recalc", "return 1;"), ACTOR);
    }

    private static SaveTriggerRequest before() {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "validate", List.of("CREATED"),
                "recalc");
        request.setTiming(TriggerDefinition.TIMING_BEFORE);
        return request;
    }

    @Test
    public void test_saves_a_before_trigger() throws Exception {
        final var response = TriggerOperationHelper.executeSave(before(), ACTOR);
        assertInstanceOf(SaveTriggerResponse.class, response, response.getMessage());
        final var stored = cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, stored.size());
        assertTrue(stored.getFirst().isBefore());
    }

    @Test
    public void test_defaults_to_after_when_timing_omitted() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit", List.of("CREATED"),
                "recalc");
        assertInstanceOf(SaveTriggerResponse.class, TriggerOperationHelper.executeSave(request, ACTOR));
        assertFalse(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).getFirst().isBefore());
    }

    @Test
    public void test_rejects_unknown_timing() throws Exception {
        final var request = before();
        request.setTiming("sideways");
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("timing"));
    }

    @Test
    public void test_rejects_batch_mode_on_a_before_trigger() throws Exception {
        final var request = before();
        request.setMode(TriggerDefinition.MODE_BATCH);
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("batch"));
    }

    @Test
    public void test_rejects_allow_cascade_on_a_before_trigger() throws Exception {
        final var request = before();
        request.setAllowCascade(true);
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("cascade"));
    }

    @Test
    public void test_allows_batch_mode_on_an_after_trigger() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit", List.of("CREATED"),
                "recalc");
        request.setMode(TriggerDefinition.MODE_BATCH);
        assertInstanceOf(SaveTriggerResponse.class, TriggerOperationHelper.executeSave(request, ACTOR));
    }

    @Test
    public void test_rejects_a_before_trigger_naming_a_missing_procedure() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "validate", List.of("CREATED"),
                "nonexistent");
        request.setTiming(TriggerDefinition.TIMING_BEFORE);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(),
                TriggerOperationHelper.executeSave(request, ACTOR).getErrorCode());
    }
}
