package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.StopListenResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenOperationTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void processListen_existingCollection_returnsListenResponse() {
        final var req = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of());

        final var resp = processor.processMessage(req, UUID.randomUUID());

        assertInstanceOf(ListenResponse.class, resp);
        assertEquals(OperationType.LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNotNull(((ListenResponse) resp).getListenId());
        assertNotNull(((ListenResponse) resp).getResultHash());
        assertNotNull(((ListenResponse) resp).getResults());
    }

    @Test
    public void processListen_withExistingDocument_returnsItInResults() {
        final var saveReq = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("listen-doc-1"));
        saveReq.setObject(obj);
        processor.processMessage(saveReq);

        final var req = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of());

        final var resp = (ListenResponse) processor.processMessage(req, UUID.randomUUID());

        assertTrue(resp.getResults().stream()
                .anyMatch(r -> "listen-doc-1".equals(r.get("_id").asJsonString().getValue())));
    }

    @Test
    public void processStopListen_validId_returnsStopListenResponse() {
        final var clientId = UUID.randomUUID();
        final var listenReq = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        listenReq.setAggregationSteps(List.of());
        final var listenResp = (ListenResponse) processor.processMessage(listenReq, clientId);

        final var stopReq = new StopListenRequest();
        stopReq.setListenId(listenResp.getListenId());
        final var stopResp = processor.processMessage(stopReq, clientId);

        assertInstanceOf(StopListenResponse.class, stopResp);
        assertEquals(OperationType.STOP_LISTEN, stopResp.getType());
        assertEquals(OperationStatus.OK, stopResp.getStatus());
    }

    @Test
    public void processStopListen_unknownId_returns404() {
        final var stopReq = new StopListenRequest();
        stopReq.setListenId(UUID.randomUUID().toString());

        final var resp = processor.processMessage(stopReq, UUID.randomUUID());

        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
        assertEquals("404-7", resp.getErrorCode());
    }

    @Test
    public void processStopListen_alreadyUnregistered_returns404() {
        final var clientId = UUID.randomUUID();
        final var listenReq = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        listenReq.setAggregationSteps(List.of());
        final var listenResp = (ListenResponse) processor.processMessage(listenReq, clientId);

        final var stopReq = new StopListenRequest();
        stopReq.setListenId(listenResp.getListenId());
        processor.processMessage(stopReq, clientId);
        final var secondStop = processor.processMessage(stopReq, clientId);

        assertEquals(OperationStatus.NOT_FOUND, secondStop.getStatus());
    }

    @Test
    public void dropCollection_dropsActiveListeners() {
        final var dropColl = "listenDropColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, dropColl));

        final var clientId = UUID.randomUUID();
        final var listenReq = new ListenRequest(TestGlobals.DB, dropColl);
        listenReq.setAggregationSteps(List.of());
        final var listenResp = (ListenResponse) processor.processMessage(listenReq, clientId);
        final var listenId = listenResp.getListenId();

        processor.processMessage(new DropCollectionRequest(TestGlobals.DB, dropColl));

        final var stopReq = new StopListenRequest();
        stopReq.setListenId(listenId);
        final var stopResp = processor.processMessage(stopReq, clientId);

        assertEquals(OperationStatus.NOT_FOUND, stopResp.getStatus());
    }

    @Test
    public void dropDatabase_dropsActiveListeners() {
        final var dropDb = "listenDropDb";
        processor.processMessage(new CreateDatabaseRequest(dropDb));
        processor.processMessage(new CreateCollectionRequest(dropDb, "coll1"));
        processor.processMessage(new CreateCollectionRequest(dropDb, "coll2"));

        final var clientId = UUID.randomUUID();
        final var listenReq1 = new ListenRequest(dropDb, "coll1");
        listenReq1.setAggregationSteps(List.of());
        final var listenId1 = ((ListenResponse) processor.processMessage(listenReq1, clientId)).getListenId();

        final var listenReq2 = new ListenRequest(dropDb, "coll2");
        listenReq2.setAggregationSteps(List.of());
        final var listenId2 = ((ListenResponse) processor.processMessage(listenReq2, clientId)).getListenId();

        processor.processMessage(new DropDatabaseRequest(dropDb));

        final var stopReq1 = new StopListenRequest();
        stopReq1.setListenId(listenId1);
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(stopReq1, clientId).getStatus());

        final var stopReq2 = new StopListenRequest();
        stopReq2.setListenId(listenId2);
        assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(stopReq2, clientId).getStatus());
    }
}
