package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureCallHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.CallProcedureResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureImportIntegrationTest {
    private static final String ADMIN = "importadmin";
    private static final String OWNER = "importowner";
    private static final String WRITER = "importwriter";
    private static final String AUDIT_COLL = "importAudit";
    private static final String OTHER_DB = "otherImportDb";
    private static final String LIB = "export function twice(n) { return n * 2; }";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var fs = IocContainer.get(FileSystem.class);
        fs.createCollectionFile(TestGlobals.DB, AUDIT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, AUDIT_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, AUDIT_COLL));
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(OTHER_DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(OTHER_DB, TestGlobals.COLL));
        fs.createDatabaseFolder(OTHER_DB);
        fs.createCollectionFile(OTHER_DB, TestGlobals.COLL);
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OWNER));
        createAdmin();
        createUser(OWNER);
        createUser(WRITER);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(TestGlobals.DB + "|" + TestGlobals.COLL, PermissionLevel.READ_WRITE);
        final var change = new ChangePermissionsRequest();
        change.setUsername(WRITER);
        change.setAdmin(false);
        change.setGlobalPermissions(new HashSet<>());
        change.setDatabasePermissions(new HashMap<>());
        change.setCollectionPermissions(collPerms);
        UserOperationHelper.processChangePermissions(change);
    }

    private static void createAdmin() {
        final var request = new CreateUserRequest();
        request.setUsername(ProcedureImportIntegrationTest.ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private static void createUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", true);
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        clearProcedures(TestGlobals.DB);
        clearProcedures(OTHER_DB);
    }

    private void clearProcedures(String dbName) {
        for (final var name : fs.listProcedureNames(dbName)) {
            fs.deleteProcedure(dbName, name);
        }
        cache.removeProceduresForDatabase(dbName);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(dbName);
    }

    private static void save(String dbName, String name, String source) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(dbName, name, source), OWNER);
    }

    private static void saveDisabled() throws Exception {
        final var request = new SaveProcedureRequest(TestGlobals.DB, "lib", ProcedureImportIntegrationTest.LIB);
        request.setEnabled(false);
        ProcedureOperationHelper.executeSave(request, OWNER);
    }

    private static RunScriptResponse run(String script) {
        return (RunScriptResponse) ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB, script, null),
                ADMIN, null);
    }

    private static CallProcedureResponse call(String name) {
        return (CallProcedureResponse) ProcedureCallHelper
                .execute(new org.techhouse.ops.req.CallProcedureRequest(TestGlobals.DB, name, null), ADMIN, null);
    }

    @Test
    public void test_run_script_imports_named_export_from_procedure() throws Exception {
        save(TestGlobals.DB, "lib", LIB);
        final var response = run("""
                import { twice } from "procedures/lib";
                return twice(21);
                """);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals(42.0, response.getResult().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_run_script_imports_default_export() throws Exception {
        save(TestGlobals.DB, "lib", "export default (n) => n + 1;");
        final var response = run("""
                import bump from "procedures/lib";
                return bump(41);
                """);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals(42.0, response.getResult().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_namespace_import_exposes_members_and_default() throws Exception {
        save(TestGlobals.DB, "lib", LIB + " export default 7;");
        final var response = run("""
                import * as ns from "procedures/lib";
                return [typeof ns.twice, ns.default].join(":");
                """);
        assertEquals("function:7", response.getResult().asJsonString().getValue(), response.getMessage());
    }

    @Test
    public void test_dynamic_import_resolves_procedure() throws Exception {
        save(TestGlobals.DB, "lib", LIB);
        final var response = run("""
                const ns = await import("procedures/lib");
                return ns.twice(4);
                """);
        assertEquals(8.0, response.getResult().asJsonNumber().getValue().doubleValue(), response.getMessage());
    }

    @Test
    public void test_procedure_imports_another_procedure() throws Exception {
        save(TestGlobals.DB, "lib", LIB);
        save(TestGlobals.DB, "caller", """
                import { twice } from "procedures/lib";
                return twice(50);
                """);
        final var response = call("caller");
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals(100.0, response.getResult().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_trigger_imports_procedure_and_keeps_definer_rights() throws Exception {
        save(TestGlobals.DB, "lib", "export function tag(by) { return 'seen-' + by; }");
        save(TestGlobals.DB, "audit", """
                import db from "db";
                import args from "args";
                import { tag } from "procedures/lib";
                db.save(db.name, "%s", { _id: args.id, note: tag(args.actingUser) });
                return "ok";
                """.formatted(AUDIT_COLL));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, false, true, OWNER, 1L, 1L, 1L, OWNER)));
        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry()), WRITER, 0));
        final var row = auditRow();
        assertNotNull(row, "the trigger's definer-rights write should have landed");
        assertEquals("seen-" + WRITER, row.get("note").asJsonString().getValue());
    }

    @Test
    public void test_top_level_return_imports_as_undefined_default() throws Exception {
        save(TestGlobals.DB, "lib", "return 5;");
        final var response = run("""
                import lib from "procedures/lib";
                return typeof lib;
                """);
        assertEquals("undefined", response.getResult().asJsonString().getValue(), response.getMessage());
    }

    // The frame for imported code is labelled with the module it was written in, not the importer's
    @Test
    public void test_stack_names_both_the_importer_and_the_imported_module() throws Exception {
        save(TestGlobals.DB, "lib", """
                export function explode() {
                  throw new Error('from the library');
                }
                """);
        final var response = run("""
                import { explode } from "procedures/lib";
                function caller() {
                  explode();
                }
                caller();
                """);
        final var stack = response.getStack();
        assertNotNull(stack, response.getMessage());
        assertTrue(stack.getFirst().startsWith("explode (procedures/lib:"), stack::toString);
        assertTrue(stack.stream().anyMatch(frame -> frame.startsWith("caller (main:")), stack::toString);
    }

    @Test
    public void test_unknown_procedure_throws_catchable_error() {
        final var response = run("""
                try {
                    await import("procedures/nope");
                    return "resolved";
                } catch (e) {
                    return e.message;
                }
                """);
        assertEquals("Cannot find module 'procedures/nope'", response.getResult().asJsonString().getValue(),
                response.getMessage());
    }

    @Test
    public void test_disabled_procedure_is_not_importable() throws Exception {
        saveDisabled();
        final var response = run("""
                import { twice } from "procedures/lib";
                return twice(1);
                """);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("Cannot find module 'procedures/lib'"), response.getMessage());
    }

    @Test
    public void test_self_import_is_circular() throws Exception {
        save(TestGlobals.DB, "loop", """
                import { self } from "procedures/loop";
                export function self() { return 1; }
                """);
        final var response = call("loop");
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("Circular import of module"), response.getMessage());
    }

    @Test
    public void test_mutual_import_is_circular() throws Exception {
        // A save refuses an import that does not resolve yet, so the pair is built dependency-first and
        // the cycle closed by updating the second one afterwards.
        save(TestGlobals.DB, "b", "export function b() { return 1; }");
        save(TestGlobals.DB, "a", "import { b } from \"procedures/b\"; export function a() { return b(); }");
        save(TestGlobals.DB, "b", "import { a } from \"procedures/a\"; export function b() { return a(); }");
        final var response = run("""
                import { a } from "procedures/a";
                return a();
                """);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("Circular import of module"), response.getMessage());
    }

    @Test
    public void test_imported_module_evaluates_once() throws Exception {
        save(TestGlobals.DB, "counting", """
                import db from "db";
                const existing = db.findById(db.name, "%s", "evalcount");
                const seen = (existing === null ? 0 : existing.seen) + 1;
                db.save(db.name, "%s", { _id: "evalcount", seen });
                export const marker = seen;
                """.formatted(TestGlobals.COLL, TestGlobals.COLL));
        final var response = run("""
                import { marker } from "procedures/counting";
                const again = await import("procedures/counting");
                return [marker, again.marker].join(":");
                """);
        assertEquals("1:1", response.getResult().asJsonString().getValue(), response.getMessage());
        final var stored = documentIn(TestGlobals.COLL, "evalcount");
        assert stored != null;
        assertEquals(1.0, stored.get("seen").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_imported_module_shares_the_run_budget() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 50_000L);
        save(TestGlobals.DB, "spin", "while (true) { }");
        final var response = run("""
                import "procedures/spin";
                return "never";
                """);
        assertEquals(ErrorCode.SCRIPT_LIMIT_EXCEEDED.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void test_nesting_beyond_max_module_depth_aborts() throws Exception {
        for (var i = 19; i >= 0; i--) {
            final var next = i == 19 ? null : "procedures/chain" + (i + 1);
            save(TestGlobals.DB, "chain" + i,
                    next == null ? "export const depth = 0;" : "import \"" + next + "\"; export const depth = 0;");
        }
        final var response = run("""
                import "procedures/chain0";
                return "deep";
                """);
        assertEquals(ErrorCode.SCRIPT_LIMIT_EXCEEDED.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void test_import_from_another_database_is_unresolvable() throws Exception {
        save(OTHER_DB, "lib", LIB);
        final var response = run("""
                import { twice } from "procedures/lib";
                return twice(1);
                """);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("Cannot find module 'procedures/lib'"), response.getMessage());
    }

    @Test
    public void test_import_disabled_by_configuration() throws Exception {
        save(TestGlobals.DB, "lib", LIB);
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", false);
        final var response = run("""
                import { twice } from "procedures/lib";
                return twice(1);
                """);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("Cannot find module 'procedures/lib'"), response.getMessage());
    }

    @Test
    public void test_saving_a_procedure_with_an_unresolvable_import_is_refused() throws Exception {
        final var response = ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "caller",
                "import { missing } from \"procedures/absent\"; export const x = missing;"), OWNER);
        assertEquals(ErrorCode.PROCEDURE_IMPORT_NOT_FOUND.getCode(), response.getErrorCode(), response.getMessage());
        assertTrue(response.getMessage().contains("procedures/absent"), response.getMessage());
        assertNull(cache.getProcedure(TestGlobals.DB, "caller"), "nothing should have been stored");
    }

    @Test
    public void test_a_disabled_import_target_is_also_refused() throws Exception {
        saveDisabled();
        final var response = ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "caller",
                "import { twice } from \"procedures/lib\"; export const x = twice;"), OWNER);
        assertEquals(ErrorCode.PROCEDURE_IMPORT_NOT_FOUND.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void test_saving_is_allowed_once_the_library_exists() throws Exception {
        save(TestGlobals.DB, "lib", LIB);
        final var response = ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "caller",
                "import { twice } from \"procedures/lib\"; export const x = twice;"), OWNER);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    @Test
    public void test_a_self_import_still_saves_and_fails_at_runtime() throws Exception {
        // The procedure's own name counts as resolvable, so the outcome does not depend on whether this is
        // the first save; the cycle is still reported when it runs.
        final var first = ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "loop",
                "import { self } from \"procedures/loop\"; export function self() { return 1; }"), OWNER);
        assertEquals(OperationStatus.OK, first.getStatus(), first.getMessage());
        assertTrue(call("loop").getMessage().contains("Circular import of module"), call("loop").getMessage());
    }

    @Test
    public void test_the_builtin_specifiers_are_never_checked() throws Exception {
        final var response = ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "builtins",
                "import db from \"db\"; import args from \"args\"; export const n = typeof db;"), OWNER);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    @Test
    public void test_a_replicated_save_skips_the_import_check() throws Exception {
        // A stamped request is a peer re-executing REPLICATE_ADMIN. It must not reject a save the
        // coordinator accepted just because its own copy of the library has not arrived yet.
        final var request = new SaveProcedureRequest(TestGlobals.DB, "caller",
                "import { missing } from \"procedures/absent\"; export const x = missing;");
        request.setStampedVersion(7L);
        request.setStampedUpdatedAt(System.currentTimeMillis());
        request.setStampedUpdatedBy(OWNER);
        final var response = ProcedureOperationHelper.executeSave(request, OWNER);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertNotNull(cache.getProcedure(TestGlobals.DB, "caller"));
    }

    @Test
    public void test_library_of_only_exports_is_storable() throws Exception {
        final var response = ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "lib", LIB),
                OWNER);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertNotNull(cache.getProcedure(TestGlobals.DB, "lib"));
    }

    @Test
    public void test_bare_specifier_still_reports_module_not_found() {
        final var response = run("""
                try {
                    await import("lodash");
                    return "resolved";
                } catch (e) {
                    return e.message;
                }
                """);
        assertEquals("Cannot find module 'lodash'", response.getResult().asJsonString().getValue(),
                response.getMessage());
    }

    private static DbEntry entry() {
        final var data = new JsonObject();
        data.add("_id", new JsonString("trig-import"));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id("trig-import");
        dbEntry.setData(data);
        return dbEntry;
    }

    private JsonObject auditRow() {
        return documentIn(AUDIT_COLL, "trig-import");
    }

    private JsonObject documentIn(String collection, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, collection);
        request.set_id(id);
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        if (response instanceof FindByIdResponse found) {
            return found.getObject();
        }
        return null;
    }
}
