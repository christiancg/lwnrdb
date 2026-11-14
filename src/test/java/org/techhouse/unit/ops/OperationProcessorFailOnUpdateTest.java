package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorFailOnUpdateTest {
    private static final String TARGET = "\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
            + TestGlobals.COLL + "\"";
    private static final String EXISTS = ErrorCode.DOCUMENT_ALREADY_EXISTS.getCode();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "syncWrites", ConfigKey.SYNC_WRITES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse send(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private OperationResponse insert(String object) {
        return send("{\"type\":\"SAVE\"," + TARGET + ",\"failOnUpdate\":true,\"object\":" + object + "}");
    }

    private OperationResponse saveFirstN1() {
        return send("{\"type\":\"SAVE\"," + TARGET + ",\"object\":{\"_id\":\"n1\",\"name\":\"first\"}}");
    }

    private JsonObject find(String id) {
        final var response = send("{\"type\":\"FIND_BY_ID\"," + TARGET + ",\"_id\":\"" + id + "\"}");
        return assertInstanceOf(FindByIdResponse.class, response).getObject();
    }

    private String nameOf(String id) {
        return find(id).get("name").asJsonString().getValue();
    }

    @Test
    public void test_an_insert_of_a_new_id_is_answered_as_an_insert() {
        final var response = assertInstanceOf(SaveResponse.class, insert("{\"_id\":\"n1\",\"name\":\"first\"}"));

        assertTrue(response.isInserted());
        assertEquals("n1", response.get_id());
        assertEquals("first", nameOf("n1"));
    }

    @Test
    public void test_an_insert_of_an_existing_id_is_refused_and_writes_nothing() {
        assertEquals(OperationStatus.OK, insert("{\"_id\":\"n1\",\"name\":\"first\"}").getStatus());

        final var response = insert("{\"_id\":\"n1\",\"name\":\"second\"}");

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals(EXISTS, response.getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_ALREADY_EXISTS.getDefaultMessage(), response.getMessage());
        assertEquals("first", nameOf("n1"));
    }

    @Test
    public void test_the_id_beside_the_object_is_checked_too() {
        assertEquals(OperationStatus.OK, saveFirstN1().getStatus());

        final var response = send(
                "{\"type\":\"SAVE\"," + TARGET + ",\"failOnUpdate\":true,\"_id\":\"n1\",\"object\":{\"name\":\"x\"}}");

        assertEquals(EXISTS, response.getErrorCode());
        assertEquals("first", nameOf("n1"));
    }

    @Test
    public void test_without_the_flag_an_existing_id_is_updated() {
        assertEquals(OperationStatus.OK, saveFirstN1().getStatus());

        final var response = send("{\"type\":\"SAVE\"," + TARGET
                + ",\"failOnUpdate\":false,\"object\":{\"_id\":\"n1\",\"name\":\"two\"}}");

        assertFalse(assertInstanceOf(SaveResponse.class, response).isInserted());
        assertEquals("two", nameOf("n1"));
    }

    @Test
    public void test_an_insert_without_an_id_gets_a_generated_one() {
        final var response = assertInstanceOf(SaveResponse.class, insert("{\"name\":\"anonymous\"}"));

        assertTrue(response.isInserted());
        assertNotNull(response.get_id());
        assertEquals("anonymous", nameOf(response.get_id()));
    }

    @Test
    public void test_existence_is_checked_before_the_schema() {
        assertEquals(OperationStatus.OK, saveFirstN1().getStatus());
        assertEquals(OperationStatus.OK,
                send("{\"type\":\"SAVE_SCHEMA\"," + TARGET
                        + ",\"schema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}}")
                        .getStatus());

        assertEquals(EXISTS, insert("{\"_id\":\"n1\",\"name\":5}").getErrorCode());
        assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(),
                insert("{\"_id\":\"n2\",\"name\":5}").getErrorCode());
    }

    @Test
    public void test_without_synced_writes_an_existing_id_is_still_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "syncWrites", false);
        assertEquals(OperationStatus.OK, insert("{\"_id\":\"n1\",\"name\":\"first\"}").getStatus());

        assertEquals(EXISTS, insert("{\"_id\":\"n1\",\"name\":\"second\"}").getErrorCode());
        assertEquals("first", nameOf("n1"));
    }
}
