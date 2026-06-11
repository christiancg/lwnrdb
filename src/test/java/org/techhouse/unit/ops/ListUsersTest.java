package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.ListUsersResponse;
import org.techhouse.test.TestUtils;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

public class ListUsersTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        // Create some test users
        createUser("alice", true);
        createUser("bob", false);
        createUser("charlie", false);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static void createUser(String username, boolean admin) {
        final var req = new CreateUserRequest();
        req.setUsername(username);
        req.setPassword("password123");
        req.setAdmin(admin);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(req);
    }

    @Test
    public void test_list_users_returns_all_users() {
        final var req = new ListUsersRequest();
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNotNull(resp.getUsers());
        assertFalse(resp.getUsers().isEmpty());
    }

    @Test
    public void test_list_users_does_not_include_password_hash() {
        final var req = new ListUsersRequest();
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        resp.getUsers().forEach(user -> assertNull(user.get("passwordHash")));
    }

    @Test
    public void test_list_users_includes_expected_fields() {
        final var req = new ListUsersRequest();
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        final var first = resp.getUsers().getFirst();
        assertNotNull(first.get("_id"));
        assertNotNull(first.get("admin"));
        assertNotNull(first.get("globalPermissions"));
        assertNotNull(first.get("databasePermissions"));
        assertNotNull(first.get("collectionPermissions"));
        assertNotNull(first.get("ownedDatabases"));
    }

    @Test
    public void test_list_users_filter_by_admin_flag() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"admin\",\"value\":true}}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        resp.getUsers().forEach(u -> assertTrue(u.get("admin").asJsonBoolean().getValue()));
    }

    @Test
    public void test_list_users_filter_by_username() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"_id\",\"value\":\"alice\"}}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals(1, resp.getUsers().size());
        assertEquals("alice", resp.getUsers().getFirst().get("_id").asJsonString().getValue());
    }

    @Test
    public void test_list_users_with_count_step() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[{\"type\":\"COUNT\"}]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals(1, resp.getUsers().size());
        assertNotNull(resp.getUsers().getFirst().get("count"));
    }

    @Test
    public void test_list_users_with_limit_step() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[{\"type\":\"LIMIT\",\"limit\":1}]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals(1, resp.getUsers().size());
    }

    @Test
    public void test_list_users_no_match_returns_not_found() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"_id\",\"value\":\"nobody\"}}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
    }

    @Test
    public void test_list_users_parser_empty_steps() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        assertNotNull(req);
        assertTrue(req.getAggregationSteps().isEmpty());
    }

    @Test
    public void test_list_users_no_steps_field() {
        final var msg = "{\"type\":\"LIST_USERS\"}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        assertNotNull(req);
        assertTrue(req.getAggregationSteps().isEmpty());
    }

    @Test
    public void test_list_users_sort_step() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"SORT\",\"fieldName\":\"_id\",\"ascending\":true}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertFalse(resp.getUsers().isEmpty());
    }

    @Test
    public void test_list_users_skip_step() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"SKIP\",\"skip\":1}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        // at least 3 users created; skipping 1 should still return some
        assertEquals(OperationStatus.OK, resp.getStatus());
    }

    @Test
    public void test_list_users_distinct_step() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"DISTINCT\",\"fieldName\":\"admin\"}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
    }

    @Test
    public void test_list_users_group_by_step() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"GROUP_BY\",\"fieldName\":\"admin\"}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
    }

    @Test
    public void test_list_users_map_step_adds_field() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[" +
                "{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"usernameLength\",\"condition\":null," +
                "\"operator\":{\"type\":\"SIZE\",\"operand\":\"_id\"}}]}" +
                "]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        final var resp = (ListUsersResponse) processor.processMessage(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        resp.getUsers().forEach(u -> assertNotNull(u.get("usernameLength")));
    }

    @Test
    public void test_list_users_validator_rejects_invalid_step() {
        // LIMIT with limit=0 is invalid per AggregationStepValidator
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[{\"type\":\"LIMIT\",\"limit\":0}]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        assertFalse(org.techhouse.ops.req.validations.RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_list_users_validator_accepts_valid_steps() {
        final var msg = "{\"type\":\"LIST_USERS\",\"aggregationSteps\":[{\"type\":\"LIMIT\",\"limit\":5}]}";
        final var req = (ListUsersRequest) RequestParser.parseRequest(msg);
        assertTrue(org.techhouse.ops.req.validations.RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_list_users_authorization_non_admin_forbidden() {
        final var user = new org.techhouse.data.admin.AdminUserEntry(
                "bob", "hash", false, new java.util.HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
        final var req = new ListUsersRequest();
        assertFalse(org.techhouse.ops.auth.AuthorizationChecker.check(req, user).isAllowed());
    }
}
