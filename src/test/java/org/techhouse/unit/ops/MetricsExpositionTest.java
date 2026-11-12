package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.DatabaseStatsHelper;
import org.techhouse.ops.MetricsExposition;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MetricsExpositionTest {
    private static final List<String> FAMILIES = List.of("build_info", "uptime_seconds", "requests_total",
            "request_duration_seconds", "connections", "connections_refused_total", "in_flight_requests",
            "unsent_response_bytes", "unsent_response_limit_bytes", "cache_bytes", "cache_limit_bytes",
            "heap_used_bytes", "heap_max_bytes", "gc_collections_total", "gc_seconds_total", "background_queued",
            "triggers_total", "trigger_queue", "schedules_total", "schedule_queue", "scripts_running",
            "scripts_rejected_total", "in_doubt_transactions", "fsync_total", "fsync_failures_total",
            "fsync_duration_seconds", "databases", "collections", "documents", "live_bytes");
    private static final String ADMIN = "metrics_exposition_admin";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        for (final var id : List.of("a", "b", "c")) {
            save(id);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.addProperty("payload", "metrics " + id);
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private static double gauge(String text, String name) {
        final var matcher = Pattern.compile("(?m)^lwnrdb_" + name + " (\\S+)$").matcher(text);
        assertTrue(matcher.find(), name + " missing from\n" + text);
        return Double.parseDouble(matcher.group(1));
    }

    @Test
    public void test_every_family_is_announced_exactly_once() {
        final var text = MetricsExposition.render();

        for (final var family : FAMILIES) {
            final var help = "# HELP lwnrdb_" + family + " ";
            final var type = "# TYPE lwnrdb_" + family + " ";
            assertEquals(1, text.split(Pattern.quote(help), -1).length - 1, family);
            assertEquals(1, text.split(Pattern.quote(type), -1).length - 1, family);
        }
        assertEquals(FAMILIES.size(), text.lines().filter(line -> line.startsWith("# TYPE ")).count());
    }

    @Test
    public void test_every_sample_belongs_to_the_family_announced_before_it() {
        String family = null;
        for (final var line : MetricsExposition.render().lines().toList()) {
            if (line.startsWith("# TYPE ")) {
                family = line.split(" ")[2];
            } else if (!line.startsWith("#")) {
                final var name = line.split("[{ ]")[0];
                assertTrue(family != null && (name.equals(family) || name.startsWith(family + "_")), line);
            }
        }
    }

    @Test
    public void test_build_info_names_the_release_and_both_format_versions() {
        final var text = MetricsExposition.render();

        assertTrue(text.contains("lwnrdb_build_info{version=\"" + ServerVersion.current() + "\",protocol_version=\""
                + Globals.PROTOCOL_VERSION + "\",storage_format_version=\"" + Globals.STORAGE_FORMAT_VERSION
                + "\"} 1\n"), text);
    }

    @Test
    public void test_the_stored_totals_match_get_database_stats() {
        final var totals = assertInstanceOf(GetDatabaseStatsResponse.class,
                DatabaseStatsHelper.processGetDatabaseStats(ADMIN)).getStats().get("totals").asJsonObject();

        final var text = MetricsExposition.render();

        assertEquals(totals.get("databaseCount").asJsonNumber().getValue().doubleValue(), gauge(text, "databases"));
        assertEquals(totals.get("collectionCount").asJsonNumber().getValue().doubleValue(), gauge(text, "collections"));
        assertEquals(totals.get("entryCount").asJsonNumber().getValue().doubleValue(), gauge(text, "documents"));
        assertEquals(totals.get("sizeBytes").asJsonNumber().getValue().doubleValue(), gauge(text, "live_bytes"));
        assertTrue(gauge(text, "documents") >= 3, text);
    }

    @Test
    public void test_no_series_is_labelled_per_collection() {
        final var text = MetricsExposition.render();

        assertFalse(text.contains(TestGlobals.COLL), text);
        assertFalse(text.contains("collection="), text);
    }

    @Test
    public void test_renders_while_another_thread_holds_the_collection_write_lock() throws Exception {
        final var locking = IocContainer.get(ResourceLocking.class);
        final var collection = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        final var locked = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = Thread.ofPlatform().start(() -> {
            try {
                locking.acquireWriteLocksNotHeld(List.of(collection), 5000);
                locked.countDown();
                release.await();
                locking.releaseWriteLocksHeldByCurrentThread(List.of(collection));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            final var rendered = CompletableFuture.supplyAsync(MetricsExposition::render).get(5, TimeUnit.SECONDS);

            assertTrue(rendered.contains("lwnrdb_documents "));
        } finally {
            release.countDown();
            holder.join();
        }
    }
}
