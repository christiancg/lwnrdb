package io.github.christiancg.lwnrdb.client.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Database;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.admin.TriggerRunDecision;
import io.github.christiancg.lwnrdb.client.admin.TriggerRunStatus;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.scripting.ProcedureDefinition;
import io.github.christiancg.lwnrdb.client.scripting.ScheduleDefinition;
import io.github.christiancg.lwnrdb.client.scripting.TriggerDefinition;
import io.github.christiancg.lwnrdb.client.scripting.TriggerEvent;
import io.github.christiancg.lwnrdb.client.scripting.TriggerInfo;
import io.github.christiancg.lwnrdb.client.scripting.TriggerTiming;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ScriptingIT extends ServerIT {
    private Database shop;

    @Override
    protected Map<String, String> config() {
        return Map.of("triggersEnabled", "true", "triggerMaxAttempts", "1", "triggerRetryBackoffMs", "100");
    }

    @BeforeAll
    public void createDatabase() {
        shop = client.database("script_it");
        shop.create();
        shop.collection("orders").create();
        shop.collection("audit").create();
    }

    @Test
    public void test_run_script_returns_its_value_and_logs() {
        final var result = shop.runScript("import args from \"args\";\nconsole.log(\"hi \" + args.name);\n"
                + "return { greeting: \"hello \" + args.name };", Document.of("name", "Ana"));
        assertEquals(Map.of("greeting", "hello Ana"), result.value());
        assertEquals("hi Ana", result.logs().get(0));
        assertEquals(2, shop.runScript("return 1 + 1;").value());
    }

    @Test
    public void test_procedures_are_saved_listed_called_and_deleted() {
        final var version = shop.procedures().save(ProcedureDefinition
                .builder("double_it", "import args from \"args\";\nreturn args.n * 2;").description("doubles").build());
        assertTrue(version > 0);
        assertTrue(shop.procedures().list().stream().anyMatch(procedure -> procedure.name().equals("double_it")));
        assertTrue(shop.procedures().list(true).stream().filter(procedure -> procedure.name().equals("double_it"))
                .findFirst().orElseThrow().source().isPresent());
        assertEquals(42, shop.procedures().call("double_it", Document.of("n", 21)).value());
        final var stale = assertThrows(LwnrdbException.class, () -> shop.procedures()
                .save(ProcedureDefinition.builder("double_it", "return 0;").ifVersion(version + 10).build()));
        assertTrue(stale.rawCode().isPresent());
        shop.procedures().delete("double_it");
        assertThrows(LwnrdbException.class, () -> shop.procedures().call("double_it"));
    }

    @Test
    public void test_triggers_fire_test_reject_and_list() throws Exception {
        shop.procedures().save(ProcedureDefinition.builder("audit_write", "import db from \"db\";\n"
                + "import args from \"args\";\ndb.save(db.name, \"audit\", { _id: args.id, event: args.event });\n"
                + "return true;").build());
        shop.procedures()
                .save(ProcedureDefinition.builder("require_total", "export default function (document) {\n"
                        + "  if (document.total == null) { throw new Error(\"total missing\"); }\n  return true;\n}")
                        .build());
        final var orders = shop.collection("orders");
        final var saved = orders.triggers()
                .save(TriggerDefinition.builder("audit", "audit_write").events(TriggerEvent.CREATED).build());
        assertTrue(saved.version() > 0);
        orders.triggers().save(TriggerDefinition.builder("guard", "require_total").events(TriggerEvent.CREATED)
                .timing(TriggerTiming.BEFORE).build());
        assertEquals(2, orders.triggers().list().size());
        assertTrue(shop.listTriggers().stream().map(TriggerInfo::name).toList()
                .containsAll(java.util.List.of("audit", "guard")));
        final var tested = orders.triggers().test("guard", TriggerEvent.CREATED, Document.of("_id", "t1"));
        assertFalse(tested.decision().isEmpty());
        final var refused = assertThrows(LwnrdbException.class, () -> orders.save(Document.of("_id", "o1")));
        assertEquals("400-21", refused.rawCode().orElseThrow());
        assertTrue(refused.getMessage().contains("total missing"), refused.getMessage());
        orders.save(Document.of("_id", "o2", "total", 5));
        final var audited = eventually(() -> shop.collection("audit").findById("o2"), Optional::isPresent);
        assertTrue(audited.isPresent());
        orders.triggers().delete("audit");
        orders.triggers().delete("guard");
        assertTrue(orders.triggers().list().isEmpty());
    }

    @Test
    public void test_dead_lettered_trigger_runs_are_listed_and_resolved() throws Exception {
        shop.procedures().save(ProcedureDefinition.builder("always_fails", "throw new Error(\"boom\");").build());
        final var notes = shop.collection("notes");
        notes.create();
        notes.triggers()
                .save(TriggerDefinition.builder("failing", "always_fails").events(TriggerEvent.CREATED).build());
        notes.save(Document.of("_id", "n1"));
        final var dead = eventually(() -> client.admin().triggerRuns().list(TriggerRunStatus.DEAD),
                runs -> !runs.isEmpty());
        assertFalse(dead.isEmpty());
        assertTrue(client.admin().triggerRuns().list().size() >= dead.size());
        assertTrue(client.admin().triggerRuns().resolve(dead.get(0).runId(), TriggerRunDecision.DISCARD));
    }

    @Test
    public void test_schedules_are_saved_listed_and_deleted() {
        shop.procedures().save(ProcedureDefinition.builder("noop", "return 0;").build());
        assertTrue(shop.schedules().save(
                ScheduleDefinition.builder("hourly", "noop").cron("0 * * * *").args(Document.of("a", 1)).build()) > 0);
        shop.schedules().save(ScheduleDefinition.builder("poll", "noop").interval(Duration.ofMinutes(5)).build());
        assertEquals(2, shop.schedules().list().size());
        assertTrue(shop.schedules().list(true).stream().anyMatch(schedule -> schedule.raw().has("args")));
        shop.schedules().delete("hourly");
        shop.schedules().delete("poll");
        assertTrue(shop.schedules().list().isEmpty());
    }

    @Test
    public void test_a_long_script_is_listed_and_cancelled() throws Exception {
        final var running = CompletableFuture.supplyAsync(() -> {
            try {
                return shop.runScript("let i = 0;\nwhile (true) { i++; }");
            } catch (LwnrdbException e) {
                return null;
            }
        });
        final var scripts = eventually(() -> client.admin().scripts().list(), list -> !list.isEmpty());
        assertFalse(scripts.isEmpty());
        assertTrue(client.admin().scripts().cancel(scripts.get(0).runId()));
        running.get(30, TimeUnit.SECONDS);
    }
}
