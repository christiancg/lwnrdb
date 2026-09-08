package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptTransactionIntegrationTest {
    private static final String ADMIN = "txscriptadmin";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        final var configuration = Configuration.getInstance();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLines", 1_000);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLineChars", 4_096);
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static RunScriptResponse run(String script) {
        return (RunScriptResponse) ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB, script, null),
                ADMIN, null);
    }

    @Test
    public void test_transaction_commits_writes_to_two_collections() {
        final var response = run("""
                import db from "db";
                db.transaction(() => {
                    db.save(db.name, "%s", { _id: "tx-a", value: "a" });
                    db.save(db.name, "%s", { _id: "tx-b", value: "b" });
                });
                return "committed";
                """.formatted(TestGlobals.COLL, TestGlobals.JOIN_COLL));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals("committed", response.getResult().asJsonString().getValue());

        final var check = run("""
                import db from "db";
                return [db.findById(db.name, "%s", "tx-a").value, db.findById(db.name, "%s", "tx-b").value].join("-");
                """.formatted(TestGlobals.COLL, TestGlobals.JOIN_COLL));
        assertEquals("a-b", check.getResult().asJsonString().getValue());
    }

    @Test
    public void test_transaction_rolls_back_on_throw_and_releases_locks() {
        final var response = run("""
                import db from "db";
                try {
                    db.transaction(() => {
                        db.save(db.name, "%s", { _id: "rollback-me", value: "x" });
                        throw new Error("abort");
                    });
                    return "committed";
                } catch (e) {
                    return e.message;
                }
                """.formatted(TestGlobals.COLL));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals("abort", response.getResult().asJsonString().getValue());

        final var check = run("""
                import db from "db";
                db.save(db.name, "%s", { _id: "after-rollback", value: "ok" });
                return db.findById(db.name, "%s", "rollback-me") === null
                        && db.findById(db.name, "%s", "after-rollback") !== null;
                """.formatted(TestGlobals.COLL, TestGlobals.COLL, TestGlobals.COLL));
        assertTrue(check.getResult().asJsonBoolean().getValue(), check.getMessage());
    }
}
