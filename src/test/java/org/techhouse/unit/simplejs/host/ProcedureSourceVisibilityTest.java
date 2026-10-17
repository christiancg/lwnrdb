package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ProcedureModuleResolver;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureSourceVisibilityTest {
    private static final String SOURCE = "export function double(n) { return n * 2; }";
    private static final String RUNNER = "srcrunner";
    private static final String MANAGER = "srcmanager";
    private static final String IMPORT_AND_SHOW = "import { double } from 'procedures/srclib';"
            + " return [String(double), double(21)].join('|');";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        saveUser(RUNNER, ScriptPermissionLevel.RUN);
        saveUser(MANAGER, ScriptPermissionLevel.MANAGE);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "srclib", SOURCE), MANAGER);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveUser(String name, ScriptPermissionLevel level) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, "unused", false, new HashSet<>(),
                new HashMap<>(Map.of(TestGlobals.DB, PermissionLevel.READ_WRITE)), new HashMap<>(),
                new HashMap<>(Map.of(TestGlobals.DB, level))));
    }

    private static String runAs(String username) {
        final var access = new EnforcingDatabaseAccess(username, null, TestGlobals.DB);
        final var result = new SimpleJs().run(IMPORT_AND_SHOW,
                DatabaseHostBindings.of(null, access, null, DatabaseHostBindings.limitsFromConfiguration(), null));
        return result.getValue().asJsonString().getValue();
    }

    @Test
    public void test_an_imported_procedure_function_to_string_is_a_native_stub_for_run_only() {
        assertEquals("function double() { [native code] }|42", runAs(RUNNER));
    }

    @Test
    public void test_a_manage_holder_sees_the_imported_source() {
        assertEquals("function double(n) { return n * 2; }|42", runAs(MANAGER));
    }

    @Test
    public void test_the_caller_decides_whether_the_source_is_readable() {
        assertFalse(new EnforcingDatabaseAccess(RUNNER, null, TestGlobals.DB).mayReadProcedureSource());
        assertTrue(new EnforcingDatabaseAccess(MANAGER, null, TestGlobals.DB).mayReadProcedureSource());
        assertFalse(new EnforcingDatabaseAccess(MANAGER, null, null).mayReadProcedureSource());
        assertFalse(new EnforcingDatabaseAccess("no-such-user", null, TestGlobals.DB).mayReadProcedureSource());
    }

    @Test
    public void test_a_hidden_resolution_carries_no_shared_parse() {
        final var hidden = Objects.requireNonNull(
                new ProcedureModuleResolver(TestGlobals.DB, false).resolve("procedures/srclib", "main"));
        assertTrue(hidden.sourceHidden());
        assertNull(hidden.compiled());
        final var revealed = Objects
                .requireNonNull(new ProcedureModuleResolver(TestGlobals.DB).resolve("procedures/srclib", "main"));
        assertFalse(revealed.sourceHidden());
    }
}
