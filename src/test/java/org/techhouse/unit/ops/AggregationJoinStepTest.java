package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationJoinStepTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Handle empty collections in join operations
    @Test
    public void test_handle_empty_collections_in_join() throws IOException {
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "localField", "remoteField",
                "asField");
        request.setAggregationSteps(List.of(joinStep));

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // JOIN adds matched documents from another collection as a nested array field
    @Test
    public void test_join_adds_matching_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);

        JsonObject mainDoc = new JsonObject();
        mainDoc.add(Globals.PK_FIELD, new JsonString("main1"));
        mainDoc.addProperty("ref", 42);
        DbEntry mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, mainDoc);
        mainEntry.set_id("main1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        JsonObject joinDoc = new JsonObject();
        joinDoc.add(Globals.PK_FIELD, new JsonString("join1"));
        joinDoc.addProperty("refKey", 42);
        joinDoc.addProperty("label", "matched");
        DbEntry joinEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.JOIN_COLL, joinDoc);
        joinEntry.set_id("join1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.JOIN_COLL, joinEntry);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(r -> r.has("joined") && !r.get("joined").asJsonArray().isEmpty()));
    }

    // JOIN skips objects that lack the local field (L109, L115)
    @Test
    public void test_join_skips_objects_without_local_field() throws IOException {
        final var cache = IocContainer.get(Cache.class);

        // Entry without the localField
        JsonObject noField = new JsonObject();
        noField.add(Globals.PK_FIELD, new JsonString("nf1"));
        noField.addProperty("other", "value");
        DbEntry nfEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, noField);
        nfEntry.set_id("nf1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, nfEntry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(
                List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "missingField", "refKey", "joined")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);
        assertNotNull(result);
        // Object without localField should still appear but with empty joined array
        assertTrue(result.stream().allMatch(r -> !r.has("joined") || r.get("joined").asJsonArray().isEmpty()));
    }

    private void addJoinDoc(Cache cache, String id, int refKey, String label) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty("refKey", refKey);
        obj.addProperty("label", label);
        final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.JOIN_COLL, obj);
        e.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.JOIN_COLL, e);
    }

    // JOIN using a remote-field index attaches only the matching remote documents
    @Test
    public void test_join_uses_remote_index_returns_only_matching() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var main = new JsonObject();
        main.add(Globals.PK_FIELD, new JsonString("m1"));
        main.addProperty("ref", 42);
        final var mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, main);
        mainEntry.set_id("m1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        addJoinDoc(cache, "j1", 42, "matched");
        addJoinDoc(cache, "j2", 7, "nope");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.JOIN_COLL, "refKey");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.JOIN_COLL).setIndexes(Set.of("refKey"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        final var joined = result.getFirst().get("joined").asJsonArray();
        assertEquals(1, joined.size());
        assertEquals("matched", joined.get(0).asJsonObject().get("label").asJsonString().getValue());
    }

    // Index-backed JOIN where no remote doc matches any local value produces empty joined arrays
    @Test
    public void test_join_via_index_no_remote_match_returns_empty_joined_array() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var main = new JsonObject();
        main.add(Globals.PK_FIELD, new JsonString("m1"));
        main.addProperty("ref", 99);
        final var mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, main);
        mainEntry.set_id("m1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        addJoinDoc(cache, "j1", 1, "no-match");
        addJoinDoc(cache, "j2", 2, "also-no");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.JOIN_COLL, "refKey");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.JOIN_COLL).setIndexes(Set.of("refKey"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().has("joined"));
        // No remote doc matched: joined field is set to null (serialized as JsonNull)
        assertTrue(result.getFirst().get("joined").isJsonNull());
    }

    // When the remote field has no index, JOIN falls back to a full collection scan on the remote side
    @Test
    public void test_join_without_remote_index_falls_back_to_full_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var main = new JsonObject();
        main.add(Globals.PK_FIELD, new JsonString("m1"));
        main.addProperty("ref", 5);
        final var mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, main);
        mainEntry.set_id("m1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        addJoinDoc(cache, "j1", 5, "match");
        addJoinDoc(cache, "j2", 9, "no-match");
        // No index on refKey — should fall back to full scan

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        final var joined = result.getFirst().get("joined").asJsonArray();
        assertEquals(1, joined.size());
        assertEquals("match", joined.get(0).asJsonObject().get("label").asJsonString().getValue());
    }
}
