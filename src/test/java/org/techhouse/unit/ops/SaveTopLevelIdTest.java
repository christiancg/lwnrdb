package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SaveTopLevelIdTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

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

    private SaveRequest withTopLevelIdOnly(String id, String name) {
        final var object = new JsonObject();
        object.addProperty("name", name);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        return request;
    }

    private void seed(String id, String name) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("name", name);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        processor.processMessage(request);
    }

    private FindByIdResponse findById(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return (FindByIdResponse) processor.processMessage(request);
    }

    @Test
    public void test_top_level_id_updates_the_named_document() {
        seed("doc1", "alpha");
        final var response = processor.processMessage(withTopLevelIdOnly("doc1", "beta"));
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("doc1", ((SaveResponse) response).get_id());

        final var found = findById("doc1");
        assertEquals(OperationStatus.OK, found.getStatus());
        assertNotNull(found.getObject());
        assertEquals("beta", found.getObject().get("name").asJsonString().getValue());
    }

    @Test
    public void test_top_level_id_insert_creates_under_that_id() {
        final var response = processor.processMessage(withTopLevelIdOnly("fresh", "gamma"));
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("fresh", ((SaveResponse) response).get_id());
        assertEquals(OperationStatus.OK, findById("fresh").getStatus());
    }

    @Test
    public void test_top_level_id_insert_is_reported_as_an_insert() {
        final var response = (SaveResponse) processor.processMessage(withTopLevelIdOnly("brand-new", "delta"));
        assertTrue(response.isInserted());
    }

    @Test
    public void test_saving_over_an_existing_document_is_reported_as_an_update() {
        seed("existing", "one");
        final var response = (SaveResponse) processor.processMessage(withTopLevelIdOnly("existing", "two"));
        assertFalse(response.isInserted());
    }

    @Test
    public void test_conflicting_top_level_and_object_id_is_rejected() {
        final var object = new JsonObject();
        object.addProperty("_id", "inside");
        object.addProperty("name", "x");
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id("outside");

        final var response = processor.processMessage(request);
        assertEquals("400-1", response.getErrorCode());
    }

    @Test
    public void test_object_id_alone_still_upserts() {
        final var object = new JsonObject();
        object.addProperty("_id", "only-in-object");
        object.addProperty("name", "first");
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        processor.processMessage(request);

        assertEquals(OperationStatus.OK, findById("only-in-object").getStatus());
    }
}
