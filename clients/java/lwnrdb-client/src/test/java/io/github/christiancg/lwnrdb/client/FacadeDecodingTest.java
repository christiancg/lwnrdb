package io.github.christiancg.lwnrdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.admin.TriggerRunStatus;
import io.github.christiancg.lwnrdb.client.scripting.ProcedureDefinition;
import io.github.christiancg.lwnrdb.client.scripting.ScheduleDefinition;
import io.github.christiancg.lwnrdb.client.scripting.TriggerDefinition;
import io.github.christiancg.lwnrdb.client.scripting.TriggerEvent;
import io.github.christiancg.lwnrdb.client.testing.ProtocolFixtures;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class FacadeDecodingTest {
    private final Recorder recorder = new Recorder();

    @BeforeEach
    public void replyWithFixtures() {
        final Map<String, ObjectNode> byType = new HashMap<>();
        ProtocolFixtures.responses().stream()
                .filter(fixture -> fixture.name().startsWith("ok.") && !fixture.name().equals("ok.aggregate_count"))
                .forEach(fixture -> {
                    final var frame = (ObjectNode) fixture.body().get("frame");
                    byType.putIfAbsent(frame.get("type").asString(), frame);
                });
        recorder.channel.replyWith(request -> byType.get(request.get("type").asString()).deepCopy());
    }

    @Test
    public void test_data_replies_decode() {
        final var saved = recorder.orders.save(Document.of("_id", "o-1"));
        assertEquals("o-1", saved.id());
        assertTrue(saved.inserted());
        final var bulk = recorder.orders.saveAll(List.of(Document.of("_id", "o-1")));
        assertEquals(List.of("o-2"), bulk.inserted());
        assertEquals(List.of("o-1"), bulk.updated());
        assertEquals("o-1", recorder.orders.findById("o-1").orElseThrow().id().orElseThrow());
        assertEquals(2, recorder.orders.query().toList().size());
        assertEquals("o-1", recorder.orders.query().first().orElseThrow().id().orElseThrow());
        assertEquals("o-1", recorder.orders.query().toDocuments().get(0).id().orElseThrow());
        final var analysis = recorder.orders.query().analyze();
        assertEquals(2, analysis.results().size());
        assertEquals("shop", recorder.orders.databaseName());
        assertEquals("orders", recorder.orders.name());
    }

    @Test
    public void test_ddl_index_and_schema_replies_decode() {
        assertEquals(List.of("orders"), recorder.shop.listCollections());
        assertEquals("shop", recorder.shop.name());
        assertEquals(List.of("email"), recorder.orders.indexes().reindex("email").rebuiltFields());
        assertTrue(recorder.orders.indexes().reindex().primaryKeyEntries().isEmpty());
        assertEquals(List.of("w"), recorder.orders.schema().save(Document.of("type", "object")));
        assertEquals("object", recorder.orders.schema().get().orElseThrow().getString("type").orElseThrow());
        assertEquals(1,
                recorder.shop.stats().getDocument("memory").orElseThrow().getLong("userCacheBytes").getAsLong());
    }

    @Test
    public void test_scripting_replies_decode() {
        final var run = recorder.shop.runScript("return 1;");
        assertEquals(Map.of("n", 1), run.value());
        assertEquals(List.of("x"), run.logs());
        assertFalse(run.logsTruncated());
        assertTrue(run.runId().isPresent());
        assertEquals(42, recorder.shop.procedures().call("recalc").value());
        assertEquals(4, recorder.shop.procedures().save(ProcedureDefinition.builder("p", "return 1;").build()));
        final var procedure = recorder.shop.procedures().list().get(0);
        assertEquals("recalc", procedure.name());
        assertEquals(4, procedure.version());
        assertTrue(procedure.enabled());
        final var saved = recorder.orders.triggers()
                .save(TriggerDefinition.builder("t", "p").events(TriggerEvent.CREATED).build());
        assertEquals(2, saved.version());
        assertEquals("alice", saved.definer());
        assertEquals("audit", recorder.orders.triggers().list().get(0).name());
        assertEquals("after", recorder.shop.listTriggers().get(0).timing());
        final var tested = recorder.orders.triggers().test("t", TriggerEvent.CREATED, Document.of("_id", "o-1"));
        assertEquals("replace", tested.decision());
        assertTrue(tested.document().isPresent());
        assertEquals(3, recorder.shop.schedules().save(ScheduleDefinition.builder("s", "p").cron("* * * * *").build()));
        assertEquals("nightly", recorder.shop.schedules().list().get(0).name());
    }

    @Test
    public void test_admin_replies_decode() {
        final var scripts = recorder.admin.scripts().list();
        assertEquals("RUN_SCRIPT", scripts.get(0).kind());
        assertTrue(recorder.admin.scripts().cancel("r"));
        final var runs = recorder.admin.triggerRuns().list(TriggerRunStatus.DEAD);
        assertEquals("DEAD", runs.get(0).status());
        assertTrue(recorder.admin.triggerRuns().resolve("r",
                io.github.christiancg.lwnrdb.client.admin.TriggerRunDecision.REPLAY));
        final var users = recorder.admin.users().list();
        assertEquals("alice", users.get(0).username());
        assertTrue(users.get(0).admin());
        final var transactions = recorder.admin.transactions().list();
        assertEquals(42000, transactions.get(0).ageMs());
    }
}
