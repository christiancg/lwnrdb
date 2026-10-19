package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.req.GetDatabaseStatsRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorStatsTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse statsAsAdmin() {
        TestUtils.ensureAdminUser("ddl_stats_admin");
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("ddl_stats_admin");
        try {
            return processor.processMessage(new GetDatabaseStatsRequest(), clientId);
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    @Test
    public void test_stats_without_a_user_has_no_node_sections() {
        final var response = (GetDatabaseStatsResponse) processor.processMessage(new GetDatabaseStatsRequest());

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(response.getStats().has("memory"));
        assertFalse(response.getStats().has("scripts"));
        assertEquals(0, response.getStats().get("databases").asJsonArray().size());
    }

    @Test
    public void test_get_database_stats_returns_populated_payload() {
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "stats_seed");
        obj.addProperty("v", 1);
        save.setObject(obj);
        save.set_id("stats_seed");
        processor.processMessage(save);

        final var response = (GetDatabaseStatsResponse) statsAsAdmin();
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationType.GET_DATABASE_STATS, response.getType());
        final var stats = response.getStats();
        assertNotNull(stats);
        assertTrue(stats.has("memory"));
        assertTrue(stats.has("totals"));
        assertTrue(stats.has("databases"));
        assertTrue(stats.has("scripts"));

        final var scripts = stats.get("scripts").asJsonObject();
        assertTrue(scripts.has("routingEnabled"));
        assertEquals(0L, scripts.get("running").asJsonNumber().getValue().longValue());
        final var admission = IocContainer.get(ScriptAdmission.class);
        assertEquals(admission.capacity(), scripts.get("capacity").asJsonNumber().getValue().longValue());
        assertEquals(admission.available(), scripts.get("available").asJsonNumber().getValue().longValue());
        assertEquals(admission.getRejected(), scripts.get("rejected").asJsonNumber().getValue().longValue());
        assertEquals(admission.getWaited(), scripts.get("waited").asJsonNumber().getValue().longValue());
        assertTrue(scripts.has("forwarded"));
        assertTrue(scripts.has("forwardFallbacks"));
        assertEquals(Configuration.getInstance().getScriptLocalityWeight(),
                scripts.get("localityWeight").asJsonNumber().getValue().intValue());
        assertTrue(scripts.has("localityPreferred"));
        assertEquals(IocContainer.get(org.techhouse.ops.ScriptRunRegistry.class).getCancelled(),
                scripts.get("cancelled").asJsonNumber().getValue().longValue());

        final var memory = stats.get("memory").asJsonObject();
        assertTrue(memory.has("heapUsedBytes"));
        assertTrue(memory.has("heapMaxBytes"));
        assertTrue(memory.has("userCacheBytes"));
        assertTrue(memory.has("maxMemoryBytes"));

        final var totals = stats.get("totals").asJsonObject();
        assertTrue(totals.get("userCount").asJsonNumber().getValue().longValue() >= 0L);
        assertTrue(totals.get("databaseCount").asJsonNumber().getValue().longValue() >= 1L);
        assertTrue(totals.get("collectionCount").asJsonNumber().getValue().longValue() >= 1L);
        assertTrue(totals.get("entryCount").asJsonNumber().getValue().longValue() >= 0L);

        final var dbs = stats.get("databases").asJsonArray().asList();
        assertFalse(dbs.isEmpty(), "expected at least one user database in the stats payload");
        final var firstDb = dbs.getFirst().asJsonObject();
        assertTrue(firstDb.has("name"));
        assertTrue(firstDb.has("collectionCount"));
        assertTrue(firstDb.has("collections"));
        final var firstCollections = firstDb.get("collections").asJsonArray().asList();
        if (!firstCollections.isEmpty()) {
            final var firstColl = firstCollections.getFirst().asJsonObject();
            assertTrue(firstColl.has("name"));
            assertTrue(firstColl.has("indexCount"));
            assertTrue(firstColl.has("indexes"));
            assertTrue(firstColl.has("pageCount"));
            assertTrue(firstColl.has("entryCount"));
            assertTrue(firstColl.has("sizeBytes"));
        }
    }
}
