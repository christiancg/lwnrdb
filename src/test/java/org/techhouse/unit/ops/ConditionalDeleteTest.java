package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.DeleteManyResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ConditionalDeleteTest {
    private static final int DOCUMENTS = 40;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", ConfigKey.DELETE_MAX_MATCHES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(String filler) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString("d" + i));
            document.add("group", new JsonString(i % 4 == 0 ? "a" : "b"));
            document.add("n", new JsonNumber(i));
            document.add("filler", new JsonString(filler));
            documents.add(document);
        }
        request.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationResponse send(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private OperationResponse deleteWhere(String condition) {
        return send("{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"condition\":" + condition + "}");
    }

    private Set<String> ids(String steps) {
        final var response = send("{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":" + steps + "}");
        final var ids = new TreeSet<String>();
        if (response instanceof AggregateResponse aggregate) {
            aggregate.getResults().forEach(row -> ids.add(row.get(Globals.PK_FIELD).asJsonString().getValue()));
        }
        return ids;
    }

    private Set<String> remaining() {
        return ids("[]");
    }

    private static String equalsGroup(String group) {
        return "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"group\",\"value\":\"" + group + "\"}";
    }

    @Test
    public void test_deletes_every_match_and_answers_the_count() {
        seed("x");

        final var response = deleteWhere(equalsGroup("a"));

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        final var remaining = remaining();
        assertEquals(30, remaining.size());
        for (var i = 0; i < DOCUMENTS; i++) {
            assertEquals(i % 4 != 0, remaining.contains("d" + i), "d" + i);
        }
    }

    @Test
    public void test_no_match_answers_ok_with_zero_and_writes_nothing() throws Exception {
        seed("x");
        final var rowsBefore = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size();

        final var response = deleteWhere(equalsGroup("missing"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(0L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(rowsBefore, cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_a_conjunction_condition_deletes_only_the_documents_matching_every_leg() {
        seed("x");

        final var response = deleteWhere("{\"conjunctionType\":\"AND\",\"operators\":[" + equalsGroup("b")
                + ",{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\",\"value\":10}]}");

        assertEquals(7L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(33, remaining().size());
    }

    @Test
    public void test_an_indexed_condition_and_a_scanned_one_delete_the_same_ids() {
        seed("x");
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "group")).getStatus());

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, deleteWhere(equalsGroup("a"))).getDeletedCount());

        final var indexed = ids("[{\"type\":\"FILTER\",\"operator\":" + equalsGroup("b") + "}]");
        final var scanned = ids(
                "[{\"type\":\"SKIP\",\"skip\":0},{\"type\":\"FILTER\",\"operator\":" + equalsGroup("b") + "}]");
        assertEquals(scanned, indexed);
        assertEquals(30, indexed.size());
        assertEquals(Set.of(), ids("[{\"type\":\"FILTER\",\"operator\":" + equalsGroup("a") + "}]"));
    }

    @Test
    public void test_more_than_delete_max_matches_is_400_44_and_deletes_nothing() throws Exception {
        seed("x");
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", "9");

        final var response = deleteWhere(equalsGroup("a"));

        assertEquals(ErrorCode.DELETE_MATCHES_TOO_MANY.getCode(), response.getErrorCode());
        assertEquals(DOCUMENTS, remaining().size());
    }

    @Test
    public void test_exactly_delete_max_matches_is_allowed() throws Exception {
        seed("x");
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", "10");

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, deleteWhere(equalsGroup("a"))).getDeletedCount());
    }

    @Test
    public void test_matches_on_several_pages_vacuum_the_emptied_ones() throws Exception {
        seed("y".repeat(200_000));
        final var pages = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).size();

        final var response = deleteWhere("{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\",\"value\":20}");

        assertEquals(20L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(20, remaining().size());
        final var live = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .mapToLong(AdminPageEntry::getPageSize).sum();
        assertEquals(20L * survivorBytes(), live, "the occupancy rows count only the survivors");
        assertEquals(pages, cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).size(),
                "an emptied page keeps its row at size 0");
    }

    private long survivorBytes() throws Exception {
        return cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of("d20")).getFirst().byteSize();
    }

    @Test
    public void test_a_script_condition_is_evaluated_per_document() {
        seed("x");

        final var response = deleteWhere("{\"script\":\"export default (d) => d.n >= 30;\"}");

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(30, remaining().size());
    }

    @Test
    public void test_a_condition_on_an_unregistered_collection_answers_404_11() {
        final var response = send("{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"neverCreated\",\"condition\":" + equalsGroup("a") + "}");

        assertEquals("404-11", response.getErrorCode());
    }
}
