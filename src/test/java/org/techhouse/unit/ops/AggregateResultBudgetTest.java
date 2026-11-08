package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ListenRegistration;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.HeldRows;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregateResultBudgetTest {
    private static final String FAN_IN_REMOTE = "fanInRemote";
    private static final String JOINED = "joinedColl";
    private static final String LISTENER = "result-budget-listener";
    private static final int LEFT_DOCUMENTS = 200;
    private static final int JOINED_DOCUMENTS = 5;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var left = new StringBuilder(LEFT_DOCUMENTS * 32);
        for (var i = 0; i < LEFT_DOCUMENTS; i++) {
            left.append(i == 0 ? "" : ",").append("{\"_id\":\"d").append(i).append("\",\"n\":").append(i)
                    .append(",\"k\":\"g\"}");
        }
        assertEquals(OperationStatus.OK,
                run(write("BULK_SAVE", TestGlobals.COLL, "\"objects\":[" + left + "]")).getStatus());
        assertEquals(OperationStatus.OK, run("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + JOINED + "\"}").getStatus());
        final var joined = new StringBuilder(JOINED_DOCUMENTS * 10_100);
        for (var i = 0; i < JOINED_DOCUMENTS; i++) {
            joined.append(i == 0 ? "" : ",").append("{\"_id\":\"j").append(i).append("\",\"k\":\"g\",\"pad\":\"")
                    .repeat("p", 10_000).append("\"}");
        }
        assertEquals(OperationStatus.OK, run(write("BULK_SAVE", JOINED, "\"objects\":[" + joined + "]")).getStatus());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes",
                ConfigKey.AGGREGATE_MAX_RESULT_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse run(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private void capAt(String size) throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes", size);
    }

    private static void drainPending() throws Exception {
        final var collection = FAN_IN_REMOTE;
        final var pending = IocContainer.get(PendingIndexWrites.class);
        for (final var id : pending.idsFor(TestGlobals.DB, collection)) {
            final var entries = IocContainer.get(Cache.class).getEntriesByIds(TestGlobals.DB, collection, Set.of(id));
            EventProcessorHelper
                    .processEvent(new EntityEvent(EventType.CREATED, TestGlobals.DB, collection, entries.getFirst()));
        }
        assertTrue(pending.idsFor(TestGlobals.DB, collection).isEmpty());
    }

    private static String write(String type, String collection, String payload) {
        return "{\"type\":\"" + type + "\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + collection + "\"," + payload + "}";
    }

    private static String aggregate(String steps) {
        return "{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[" + steps + "]}";
    }

    private static String concatOf(int literalBytes) {
        return "{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"big\",\"operator\":{\"type\":\"CONCAT\","
                + "\"operands\":[\"-" + "a".repeat(literalBytes) + "\",\"-b\"]}}]}";
    }

    private static String joinOnK() {
        return "{\"type\":\"JOIN\",\"joinCollection\":\"" + JOINED
                + "\",\"localField\":\"k\",\"remoteField\":\"k\",\"asField\":\"m\"}";
    }

    private static String firstRows(int count) {
        return "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\",\"value\":"
                + count + "}}";
    }

    @Test
    public void mapConcatAcrossManyRowsIsRefusedWith400_36() throws Exception {
        capAt("1Mb");
        final var response = run(aggregate(concatOf(10_240)));
        assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void joinRowsSharingOneLargeGroupAreChargedPerRow() throws Exception {
        capAt("1Mb");
        final var refused = run(aggregate(firstRows(50) + "," + joinOnK()));
        assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE.getCode(), refused.getErrorCode(), refused.getMessage());
        capAt("8Mb");
        final var answered = run(aggregate(firstRows(50) + "," + joinOnK()));
        assertEquals(OperationStatus.OK, answered.getStatus(), answered.getMessage());
    }

    @Test
    public void anIndexedJoinFanInIsCharged() throws Exception {
        final var remote = FAN_IN_REMOTE;
        assertEquals(OperationStatus.OK, run("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + remote + "\"}").getStatus());
        final var fanIn = new StringBuilder(200 * 10_100);
        for (var i = 0; i < 200; i++) {
            fanIn.append(i == 0 ? "" : ",").append("{\"_id\":\"f").append(i).append("\",\"k\":1,\"pad\":\"")
                    .repeat("p", 10_000).append("\"}");
        }
        assertEquals(OperationStatus.OK, run(write("BULK_SAVE", remote, "\"objects\":[" + fanIn + "]")).getStatus());
        assertEquals(OperationStatus.OK, run(write("CREATE_INDEX", remote, "\"fieldName\":\"k\"")).getStatus());
        drainPending();
        final var steps = firstRows(2) + ",{\"type\":\"JOIN\",\"joinCollection\":\"" + remote
                + "\",\"localField\":\"n\",\"remoteField\":\"k\",\"asField\":\"m\"},"
                + "{\"type\":\"LIMIT\",\"limit\":1}";
        final var uncapped = run(aggregate(steps));
        assertEquals(OperationStatus.OK, uncapped.getStatus(), uncapped.getMessage());
        capAt("1Mb");
        final var refused = run(aggregate(steps));
        assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE.getCode(), refused.getErrorCode(), refused.getMessage());
    }

    @Test
    public void resultRowsAreChargedTheirModelledHeap() throws Exception {
        final var rows = ((AggregateResponse) run(aggregate(""))).getResults();
        assertEquals(LEFT_DOCUMENTS, rows.size());
        var modelled = 0L;
        for (final var row : rows) {
            modelled += HeldRows.bytesOf(row);
        }
        capAt(String.valueOf(modelled * 9 / 10));
        final var refused = run(aggregate(""));
        assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE.getCode(), refused.getErrorCode(), refused.getMessage());
        capAt(String.valueOf(modelled * 11 / 10));
        assertEquals(OperationStatus.OK, run(aggregate("")).getStatus());
    }

    @Test
    public void budgetIsPerRequest() throws Exception {
        capAt("2Mb");
        for (var i = 0; i < 2; i++) {
            final var response = run(aggregate(concatOf(3_000)));
            assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        }
    }

    @Test
    public void indexOnlyCountIsNotAffected() throws Exception {
        assertEquals(OperationStatus.OK,
                run(write("CREATE_INDEX", TestGlobals.COLL, "\"fieldName\":\"n\"")).getStatus());
        capAt("1Kb");
        final var response = run(aggregate(firstRows(150) + ",{\"type\":\"COUNT\"}"));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        final var results = ((AggregateResponse) response).getResults();
        assertEquals(150, results.getFirst().get("count").asJsonNumber().getValue().intValue(), results.toString());
    }

    @Test
    public void listenRerunUsesTheSameBudget() throws Exception {
        capAt("1");
        final var refused = rerunOnce();
        assertFalse(refused.pushed().contains(refused.listenId().toString()), refused.pushed());
        assertEquals(STALE_HASH, refused.manager().getRegistration(refused.listenId()).lastHash().get());
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes",
                ConfigKey.AGGREGATE_MAX_RESULT_BYTES.defaultValue());
        final var answered = rerunOnce();
        assertTrue(answered.pushed().contains(answered.listenId().toString()), answered.pushed());
    }

    private static final String STALE_HASH = "stale-hash-for-result-budget";

    private record Rerun(UUID listenId, ListenManager manager, String pushed) {
    }

    private Rerun rerunOnce() throws Exception {
        TestUtils.ensureAdminUser(LISTENER);
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient(LISTENER);
        final var pushed = new ByteArrayOutputStream();
        clientTracker.registerOutbound(clientId, TestUtils.inlineOutbound(pushed));
        final var sentinel = new UUID[1];
        final var sentinelReached = new CountDownLatch(1);
        final var manager = new ListenManager() {
            @Override
            public ListenRegistration getRegistration(UUID id) {
                if (id.equals(sentinel[0])) {
                    sentinelReached.countDown();
                }
                return super.getRegistration(id);
            }
        };
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        final var listenId = manager.register(clientId, request, STALE_HASH);
        manager.markDelivered(listenId);
        final var sentinelRequest = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        sentinelRequest.setAggregationSteps(List.of(new LimitAggregationStep(1)));
        final var sentinelId = manager.register(clientId, sentinelRequest, STALE_HASH);
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(manager.getRegistration(listenId).groupId());
        queue.offer(manager.getRegistration(sentinelId).groupId());
        sentinel[0] = sentinelId;
        final var worker = new Thread(new ListenProcessorThread(queue, manager));
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(sentinelReached.await(10, TimeUnit.SECONDS));
        } finally {
            worker.interrupt();
            worker.join(5_000);
            clientTracker.removeById(clientId);
        }
        assertNotNull(manager.getRegistration(listenId));
        return new Rerun(listenId, manager, pushed.toString(StandardCharsets.UTF_8));
    }
}
