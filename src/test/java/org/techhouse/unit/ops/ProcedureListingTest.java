package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.ListProceduresResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureListingTest {
    private static final String RUNNER = "listing_runner";
    private static final String MANAGER = "listing_manager";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        saveUser(RUNNER, ScriptPermissionLevel.RUN);
        saveUser(MANAGER, ScriptPermissionLevel.MANAGE);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "listed", "return 1;"), MANAGER);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveUser(String name, ScriptPermissionLevel scriptLevel) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, "h", false, Set.of(),
                new HashMap<>(Map.of(TestGlobals.DB, PermissionLevel.READ)), new HashMap<>(),
                new HashMap<>(Map.of(TestGlobals.DB, scriptLevel))));
    }

    private static JsonObject onlySummaryFor(String actingUser) {
        final var response = assertInstanceOf(ListProceduresResponse.class,
                ProcedureOperationHelper.executeList(new ListProceduresRequest(TestGlobals.DB), actingUser));
        assertEquals(1, response.getProcedures().size());
        return response.getProcedures().getFirst();
    }

    @Test
    public void runOnlyCallerGetsNoSourceHash() {
        final var summary = onlySummaryFor(RUNNER);
        assertFalse(summary.has("sourceHash"));
        assertFalse(summary.has("source"));
        assertTrue(summary.has("version"));
    }

    @Test
    public void managerAndInternalCallerKeepTheSourceHash() {
        assertTrue(onlySummaryFor(MANAGER).has("sourceHash"));
        assertTrue(onlySummaryFor(null).has("sourceHash"));
    }
}
