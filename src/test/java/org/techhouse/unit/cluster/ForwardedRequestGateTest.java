package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ForwardedRequestGate;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ForwardedRequestGateTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private static final String ADMIN = "gateadmin";
    private static final String READER = "gatereader";
    private static final String WRITER = "gatewriter";

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        createUser(READER, PermissionLevel.READ);
        createUser(WRITER, PermissionLevel.READ_WRITE);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static void createUser(String username, PermissionLevel level) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>(Map.of(TestGlobals.DB, level)));
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private static OperationRequest save(String db, String coll) {
        return RequestParser.parseRequest("{\"type\":\"SAVE\",\"databaseName\":\"" + db + "\",\"collectionName\":\""
                + coll + "\",\"object\":{\"_id\":\"g1\",\"v\":1}}");
    }

    private static OperationRequest parse(String json) {
        return RequestParser.parseRequest(json);
    }

    private static void assertRefused(ErrorCode expected, OperationResponse response) {
        assertNotNull(response, "expected a refusal with " + expected.getCode());
        assertEquals(expected.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void anInvalidNameIsRefusedWith400_1() {
        assertRefused(ErrorCode.VALIDATION_ERROR, ForwardedRequestGate.check(save("../x", TestGlobals.COLL), ADMIN));
    }

    @Test
    public void aReservedDatabaseIsRefused() {
        assertRefused(ErrorCode.VALIDATION_ERROR,
                ForwardedRequestGate.check(parse(
                        "{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"admin\",\"collectionName\":\"users2\"}"),
                        ADMIN));
    }

    @Test
    public void aNullActingUserIsRefusedWith401_1() {
        assertRefused(ErrorCode.MUST_AUTHENTICATE_FIRST,
                ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), null));
        assertRefused(ErrorCode.MUST_AUTHENTICATE_FIRST,
                ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), " "));
    }

    @Test
    public void anUnknownActingUserIsRefusedWith401_2() {
        assertRefused(ErrorCode.USER_NO_LONGER_EXISTS,
                ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), "nobody"));
    }

    @Test
    public void usernamesCompareLikeTheEdge() {
        final var otherCase = ADMIN.toUpperCase(Locale.ROOT);
        final var response = ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), otherCase);
        if (cache.getAdminUserEntry(otherCase) != null) {
            assertNull(response);
        } else {
            assertRefused(ErrorCode.USER_NO_LONGER_EXISTS, response);
        }
    }

    @Test
    public void aUserWithoutPermissionIsRefusedWith403_1() {
        assertRefused(ErrorCode.NO_PERMISSIONS,
                ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), READER));
    }

    @Test
    public void aUserWithPermissionPasses() {
        assertNull(ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), WRITER));
        assertNull(ForwardedRequestGate.check(parse("{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"g1\"}"), READER));
    }

    @Test
    public void anAdminPasses() {
        assertNull(ForwardedRequestGate.check(save(TestGlobals.DB, TestGlobals.COLL), ADMIN));
        assertNull(ForwardedRequestGate.check(parse("{\"type\":\"LIST_USERS\"}"), ADMIN));
    }

    @Test
    public void aScriptRunsHistorySaveWithANullUserPasses() {
        assertNull(ForwardedRequestGate.check(save(TestGlobals.DB, "script_runs"), null));
        assertNull(ForwardedRequestGate.check(parse("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\""
                + TestGlobals.DB + "\",\"collectionName\":\"script_runs\"}"), null));
        assertNull(ForwardedRequestGate.check(parse("{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"script_runs\",\"_id\":\"r1\"}"), READER));
    }

    @Test
    public void aScriptRunsHistoryWriteWithABadDatabaseNameIsStillRefused() {
        assertRefused(ErrorCode.VALIDATION_ERROR, ForwardedRequestGate.check(save("../x", "script_runs"), null));
        assertRefused(ErrorCode.VALIDATION_ERROR, ForwardedRequestGate.check(save("admin", "script_runs"), null));
    }

    @Test
    public void aNonHistoryWriteToScriptRunsIsRefusedLikeAClient() {
        assertRefused(ErrorCode.VALIDATION_ERROR,
                ForwardedRequestGate.check(parse("{\"type\":\"BULK_SAVE\",\"databaseName\":\"" + TestGlobals.DB
                        + "\",\"collectionName\":\"script_runs\",\"objects\":[{\"_id\":\"r1\"}]}"), ADMIN));
        assertRefused(ErrorCode.VALIDATION_ERROR,
                ForwardedRequestGate.check(save(TestGlobals.DB, "Script_Runs"), null));
    }

    @Test
    public void transactionControlPassesForAnyKnownUser() {
        for (final var type : new String[]{"START_TRANSACTION", "COMMIT_TRANSACTION", "ROLLBACK_TRANSACTION"}) {
            assertNull(ForwardedRequestGate.checkTransactional(parse("{\"type\":\"" + type + "\"}"), READER), type);
        }
        assertRefused(ErrorCode.USER_NO_LONGER_EXISTS,
                ForwardedRequestGate.checkTransactional(parse("{\"type\":\"COMMIT_TRANSACTION\"}"), "nobody"));
    }

    @Test
    public void aCommitFinishingAHeldTransactionIsLetThroughForADeletedUser() {
        assertNull(ForwardedRequestGate.checkTransactional(parse("{\"type\":\"COMMIT_TRANSACTION\"}"), "nobody", true));
        assertRefused(ErrorCode.USER_NO_LONGER_EXISTS,
                ForwardedRequestGate.checkTransactional(parse("{\"type\":\"COMMIT_TRANSACTION\"}"), "nobody", false));
        assertRefused(ErrorCode.USER_NO_LONGER_EXISTS,
                ForwardedRequestGate.checkTransactional(save(TestGlobals.DB, TestGlobals.COLL), "nobody", true));
    }

    @Test
    public void aRollbackIsLetThroughEvenForADeletedUser() {
        assertNull(ForwardedRequestGate.checkTransactional(parse("{\"type\":\"ROLLBACK_TRANSACTION\"}"), "nobody"));
        assertRefused(ErrorCode.NO_PERMISSIONS,
                ForwardedRequestGate.checkTransactional(save(TestGlobals.DB, TestGlobals.COLL), READER));
    }
}
