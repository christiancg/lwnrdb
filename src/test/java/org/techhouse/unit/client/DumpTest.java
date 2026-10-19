package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.ClientOptions;
import org.techhouse.client.Connection;
import org.techhouse.client.Dump;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.unit.client.support.FakeLineServer;
import org.techhouse.unit.client.support.FakeSource;

public class DumpTest {
    private static final String HELPER = "{\"name\":\"helper\",\"source\":\"export default 1;\",\"enabled\":true}";
    private static final String TOTAL = "{\"name\":\"total\",\"source\":\"import h from 'procedures/helper';"
            + " export default h;\",\"enabled\":true,\"version\":3,\"definer\":\"bob\"}";
    private static final String UNUSED = "{\"name\":\"unused\",\"source\":\"export default 2;\",\"enabled\":false}";
    private static final String AUDIT = "{\"collectionName\":\"orders\",\"name\":\"audit\",\"events\":[\"CREATED\"],"
            + "\"procedureName\":\"total\",\"mode\":\"document\",\"timing\":\"before\",\"enabled\":true}";
    private static final String NOTIFY = "{\"collectionName\":\"empty\",\"name\":\"notify\",\"events\":[\"CREATED\"],"
            + "\"procedureName\":\"unused\",\"mode\":\"document\",\"timing\":\"after\",\"enabled\":true}";
    private static final String NIGHTLY = "{\"name\":\"nightly\",\"procedureName\":\"unused\",\"cron\":\"0 3 * * *\","
            + "\"args\":{\"limit\":7},\"enabled\":true}";
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private FakeLineServer server;
    private FakeSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
        source = new FakeSource().database("shop", "orders", "script_runs", "empty").database("crm", "leads")
                .documents("shop", "orders", 2500, 10).documents("shop", "script_runs", 3, 10)
                .documents("crm", "leads", 2, 10).index("shop", "orders", "customer", "total")
                .schema("shop", "orders", "{\"type\":\"object\"}").procedure("shop", TOTAL).procedure("shop", UNUSED)
                .procedure("shop", HELPER).trigger("shop", AUDIT).trigger("shop", NOTIFY).schedule("shop", NIGHTLY);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int dump(String... args) throws IOException {
        source.serveOn(server);
        return runDump(args);
    }

    private int runDump(String... args) throws IOException {
        final var all = new ArrayList<>(List.of("--dump"));
        all.addAll(List.of(args));
        final var options = ClientOptions.parse(all.toArray(String[]::new), Map.of());
        try (var connection = server.connect()) {
            return new Dump(connection, options, stdout, new PrintStream(stderr, true, StandardCharsets.UTF_8)).run();
        }
    }

    private List<JsonObject> emitted(byte[] bytes) {
        final var requests = new ArrayList<JsonObject>();
        for (final var line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
            requests.add(eJson.fromJson(line, JsonObject.class));
        }
        return requests;
    }

    private List<JsonObject> emitted() {
        return emitted(stdout.toByteArray());
    }

    private List<String> emittedTypes() {
        return emitted().stream().map(request -> Connection.stringOf(request, "type")).toList();
    }

    private static List<String> summaries(List<JsonObject> requests) {
        final var summaries = new ArrayList<String>();
        for (final var request : requests) {
            final var target = Connection.stringOf(request, "name") != null
                    ? Connection.stringOf(request, "name")
                    : Connection.stringOf(request, "collectionName");
            summaries.add(Connection.stringOf(request, "type") + " " + Connection.stringOf(request, "databaseName")
                    + (target == null ? "" : "." + target));
        }
        return summaries;
    }

    private static int documentsIn(List<JsonObject> requests) {
        return requests.stream().filter(request -> "BULK_SAVE".equals(Connection.stringOf(request, "type")))
                .mapToInt(request -> request.get("objects").asJsonArray().size()).sum();
    }

    private String errors() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_emits_requests_in_restore_order() throws IOException {
        assertEquals(ClientException.OK, dump());

        assertEquals(List.of("CREATE_DATABASE shop", "CREATE_COLLECTION shop.orders", "BULK_SAVE shop.orders",
                "CREATE_COLLECTION shop.empty", "SAVE_SCHEMA shop.orders", "CREATE_INDEX shop.orders",
                "CREATE_INDEX shop.orders", "SAVE_PROCEDURE shop.unused", "SAVE_PROCEDURE shop.helper",
                "SAVE_PROCEDURE shop.total", "SAVE_TRIGGER shop.audit", "SAVE_TRIGGER shop.notify",
                "SAVE_SCHEDULE shop.nightly", "CREATE_DATABASE crm", "CREATE_COLLECTION crm.leads",
                "BULK_SAVE crm.leads"), summaries(emitted()));
        assertTrue(errors().contains("shop.orders: 2500 documents"), errors());
        assertTrue(errors().contains("shop.empty: 0 documents"), errors());
    }

    @Test
    public void test_definitions_drop_versions_and_definers() throws IOException {
        dump();

        final var total = emitted().stream().filter(request -> "total".equals(Connection.stringOf(request, "name")))
                .findFirst().orElseThrow();
        assertFalse(total.has("version"));
        assertFalse(total.has("definer"));
        assertEquals("import h from 'procedures/helper'; export default h;", Connection.stringOf(total, "script"));
        final var nightly = emitted().stream()
                .filter(request -> "SAVE_SCHEDULE".equals(Connection.stringOf(request, "type"))).findFirst()
                .orElseThrow();
        assertEquals("{\"limit\":7}", eJson.toJson(nightly.get("args")));
    }

    @Test
    public void test_skips_script_runs() throws IOException {
        dump();

        assertTrue(emitted().stream()
                .noneMatch(request -> "script_runs".equals(Connection.stringOf(request, "collectionName"))));
    }

    @Test
    public void test_pages_until_no_results() throws IOException {
        dump("--databases", "shop");

        final var pages = server.requests().stream()
                .filter(request -> "AGGREGATE".equals(Connection.stringOf(request, "type"))
                        && "orders".equals(Connection.stringOf(request, "collectionName")))
                .map(request -> request.get("aggregationSteps").asJsonArray().get(0).asJsonObject().get("skip")
                        .asJsonNumber().getValue().intValue())
                .toList();
        assertEquals(List.of(0, Dump.PAGE_SIZE, 2 * Dump.PAGE_SIZE, 2500), pages);
        assertEquals(2500, documentsIn(emitted()));
    }

    @Test
    public void test_bulk_save_lines_split_at_the_byte_cap() throws IOException {
        source.documents("crm", "leads", 9, 1_000_000);

        dump("--databases", "crm");

        final var saves = emitted().stream().filter(request -> "BULK_SAVE".equals(Connection.stringOf(request, "type")))
                .toList();
        assertEquals(List.of(4, 4, 1), saves.stream().map(save -> save.get("objects").asJsonArray().size()).toList());
        for (final var save : saves) {
            assertTrue(eJson.toJson(save.get("objects")).length() <= Dump.BATCH_BYTES + 2);
        }
    }

    @Test
    public void test_databases_filter() throws IOException {
        dump("--databases", "crm");

        assertEquals(List.of("CREATE_DATABASE crm", "CREATE_COLLECTION crm.leads", "BULK_SAVE crm.leads"),
                summaries(emitted()));
    }

    @Test
    public void test_unknown_database_in_filter_fails() {
        final var error = assertThrows(ClientException.class, () -> dump("--databases", "crm,ghost"));

        assertEquals(ClientException.FAILED, error.exitCode());
        assertEquals(0, stdout.size());
    }

    @Test
    public void test_no_schema_emits_no_save_schema() throws IOException {
        dump("--databases", "crm");

        assertTrue(emitted().stream().noneMatch(request -> "SAVE_SCHEMA".equals(Connection.stringOf(request, "type"))));
    }

    @Test
    public void test_scripts_disabled_skips_definitions_and_their_triggers_with_a_stderr_note() throws IOException {
        source.serveOn(server);
        server.reply("LIST_PROCEDURES", FakeLineServer.error("LIST_PROCEDURES", "FORBIDDEN", "403-2"));
        server.reply("LIST_SCHEDULES", FakeLineServer.error("LIST_SCHEDULES", "FORBIDDEN", "403-2"));

        assertEquals(ClientException.OK, runDump("--databases", "shop"));

        final var types = emittedTypes();
        assertFalse(types.contains("SAVE_PROCEDURE"));
        assertFalse(types.contains("SAVE_SCHEDULE"));
        assertFalse(types.contains("SAVE_TRIGGER"));
        assertTrue(types.contains("BULK_SAVE"));
        assertTrue(errors().contains("note: procedures of shop were not dumped"), errors());
        assertTrue(errors().contains("note: triggers of shop were not dumped"), errors());
        assertTrue(errors().contains("note: schedules of shop were not dumped"), errors());
    }

    @Test
    public void test_source_requires_manage_skips_procedures_and_their_triggers_with_a_note() throws IOException {
        source.serveOn(server);
        server.reply("LIST_PROCEDURES", FakeLineServer.error("LIST_PROCEDURES", "FORBIDDEN", "403-6"));

        assertEquals(ClientException.OK, runDump("--databases", "shop"));

        final var types = emittedTypes();
        assertFalse(types.contains("SAVE_PROCEDURE"));
        assertFalse(types.contains("SAVE_TRIGGER"));
        assertTrue(types.contains("SAVE_SCHEDULE"));
        assertTrue(types.contains("SAVE_SCHEMA"));
        assertTrue(server.types().stream().noneMatch("LIST_TRIGGERS"::equals));
    }

    @Test
    public void test_args_require_manage_skips_schedules_with_a_note() throws IOException {
        source.serveOn(server);
        server.reply("LIST_SCHEDULES", FakeLineServer.error("LIST_SCHEDULES", "FORBIDDEN", "403-6"));

        assertEquals(ClientException.OK, runDump("--databases", "shop"));

        final var types = emittedTypes();
        assertFalse(types.contains("SAVE_SCHEDULE"));
        assertTrue(types.contains("SAVE_TRIGGER"));
        assertTrue(errors().contains("note: schedules of shop were not dumped"), errors());
    }

    @Test
    public void test_whole_server_dump_skips_a_forbidden_database_with_a_note() throws IOException {
        source.serveOn(server);
        server.respond("LIST_COLLECTIONS",
                request -> "crm".equals(Connection.stringOf(request, "databaseName"))
                        ? FakeLineServer.error("LIST_COLLECTIONS", "FORBIDDEN", "403-1")
                        : FakeLineServer.ok("LIST_COLLECTIONS", "\"collections\":[\"orders\"]"));

        assertEquals(ClientException.OK, runDump());

        assertTrue(emitted().stream().noneMatch(request -> "crm".equals(Connection.stringOf(request, "databaseName"))));
        assertTrue(emittedTypes().contains("BULK_SAVE"));
        assertTrue(errors().contains("note: crm was not dumped"), errors());
    }

    @Test
    public void test_a_forbidden_database_named_in_databases_fails() {
        source.serveOn(server);
        server.reply("LIST_COLLECTIONS", FakeLineServer.error("LIST_COLLECTIONS", "FORBIDDEN", "403-1"));

        final var error = assertThrows(ClientException.class, () -> runDump("--databases", "crm"));

        assertEquals(ClientException.FAILED, error.exitCode());
        assertEquals(0, stdout.size());
    }

    @Test
    public void test_a_non_forbidden_listing_error_still_fails() {
        source.serveOn(server);
        server.reply("LIST_COLLECTIONS", FakeLineServer.error("LIST_COLLECTIONS", "ERROR", "500-8"));

        assertEquals(ClientException.FAILED, assertThrows(ClientException.class, this::runDump).exitCode());
    }

    @Test
    public void test_a_refused_listing_stops_the_dump() throws IOException {
        source.serveOn(server);
        server.reply("LIST_TRIGGERS", FakeLineServer.error("LIST_TRIGGERS", "ERROR", "500-8"));
        final var options = ClientOptions.parse(new String[]{"--dump", "--databases", "crm"}, Map.of());
        try (var connection = server.connect()) {
            final var dump = new Dump(connection, options, stdout,
                    new PrintStream(stderr, true, StandardCharsets.UTF_8));
            assertEquals(ClientException.FAILED, assertThrows(ClientException.class, dump::run).exitCode());
        }
    }

    @Test
    public void test_compressed_output_gunzips_to_the_plain_output() throws IOException {
        dump();
        final var plain = stdout.toByteArray();
        stdout.reset();
        server.close();
        server = new FakeLineServer();

        dump("--compress");

        final var compressed = stdout.toByteArray();
        assertEquals(0x1f, compressed[0] & 0xff);
        assertEquals(0x8b, compressed[1] & 0xff);
        try (var gunzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals(plain, gunzip.readAllBytes());
        }
    }

    @Test
    public void test_failure_mid_dump_returns_1_and_leaves_a_finished_gzip_stream() throws IOException {
        source.serveOn(server);
        server.reply("GET_SCHEMA", FakeLineServer.error("GET_SCHEMA", "ERROR", "503-11"));
        final var options = ClientOptions.parse(new String[]{"--dump", "--compress"}, Map.of());
        try (var connection = server.connect()) {
            final var dump = new Dump(connection, options, stdout,
                    new PrintStream(stderr, true, StandardCharsets.UTF_8));
            assertEquals(ClientException.FAILED, assertThrows(ClientException.class, dump::run).exitCode());
        }

        try (var gunzip = new GZIPInputStream(new ByteArrayInputStream(stdout.toByteArray()))) {
            final var partial = emitted(gunzip.readAllBytes());
            assertEquals(List.of("CREATE_DATABASE shop", "CREATE_COLLECTION shop.orders", "BULK_SAVE shop.orders",
                    "CREATE_COLLECTION shop.empty"), summaries(partial));
        }
    }

    @Test
    public void test_collections_emit_only_the_named_collections() throws IOException {
        dump("--collections", "shop.orders");

        final var summaries = summaries(emitted());
        assertEquals(
                List.of("CREATE_DATABASE shop", "CREATE_COLLECTION shop.orders", "BULK_SAVE shop.orders",
                        "SAVE_SCHEMA shop.orders", "CREATE_INDEX shop.orders", "CREATE_INDEX shop.orders",
                        "SAVE_PROCEDURE shop.helper", "SAVE_PROCEDURE shop.total", "SAVE_TRIGGER shop.audit"),
                summaries);
    }

    @Test
    public void test_collections_keep_only_their_triggers() throws IOException {
        dump("--collections", "shop.empty");

        assertEquals(List.of("CREATE_DATABASE shop", "CREATE_COLLECTION shop.empty", "SAVE_PROCEDURE shop.unused",
                "SAVE_TRIGGER shop.notify"), summaries(emitted()));
    }

    @Test
    public void test_collections_keep_only_the_procedures_their_triggers_need_with_imports() throws IOException {
        dump("--collections", "shop.orders");

        final var procedures = emitted().stream()
                .filter(request -> "SAVE_PROCEDURE".equals(Connection.stringOf(request, "type")))
                .map(request -> Connection.stringOf(request, "name")).toList();
        assertEquals(List.of("helper", "total"), procedures);
    }

    @Test
    public void test_collections_emit_no_schedules() throws IOException {
        dump("--collections", "shop.orders,shop.empty");

        assertTrue(
                emitted().stream().noneMatch(request -> "SAVE_SCHEDULE".equals(Connection.stringOf(request, "type"))));
        assertTrue(server.types().stream().noneMatch("LIST_SCHEDULES"::equals));
    }

    @Test
    public void test_collections_from_two_databases_emit_both_databases_in_listed_order() throws IOException {
        dump("--collections", "crm.leads,shop.empty");

        assertEquals(List.of("CREATE_DATABASE crm", "CREATE_COLLECTION crm.leads", "BULK_SAVE crm.leads",
                "CREATE_DATABASE shop", "CREATE_COLLECTION shop.empty", "SAVE_PROCEDURE shop.unused",
                "SAVE_TRIGGER shop.notify"), summaries(emitted()));
    }

    @Test
    public void test_unknown_collection_fails() {
        final var error = assertThrows(ClientException.class, () -> dump("--collections", "shop.orders,crm.ghost"));

        assertEquals(ClientException.FAILED, error.exitCode());
        assertEquals(0, stdout.size());
    }

    @Test
    public void test_script_runs_collection_refused() {
        final var error = assertThrows(ClientException.class, () -> dump("--collections", "shop.script_runs"));

        assertEquals(ClientException.FAILED, error.exitCode());
    }

    @Test
    public void test_an_empty_server_dumps_nothing() throws IOException {
        source = new FakeSource();

        assertEquals(ClientException.OK, dump());

        assertEquals(0, stdout.size());
    }
}
