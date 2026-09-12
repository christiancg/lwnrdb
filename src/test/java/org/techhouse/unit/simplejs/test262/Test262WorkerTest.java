package org.techhouse.unit.simplejs.test262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class Test262WorkerTest {
    private static final Path FIXTURES = Path.of("test_utils", "test262", "fixtures");
    private static final Path SHIMS = Path.of("test_utils", "test262", "shims");

    private final EJson eJson = new EJson();

    private JsonObject job(String fixture, List<String> includes, List<String> flags, String negativeType) {
        final var job = new JsonObject();
        job.addProperty("id", fixture);
        job.addProperty("path", FIXTURES.resolve("test").resolve(fixture).toString());
        job.addProperty("harnessDir", FIXTURES.resolve("harness").toString());
        job.addProperty("shimDir", SHIMS.toString());
        job.add("includes", strings(includes));
        job.add("flags", strings(flags));
        job.addProperty("negativeType", negativeType);
        job.addProperty("negativePhase", "");
        return job;
    }

    private JsonArray strings(List<String> values) {
        final var array = new JsonArray();
        values.forEach(value -> array.add(new JsonString(value)));
        return array;
    }

    private List<JsonObject> drive(List<JsonObject> jobs) {
        final var input = new StringBuilder();
        jobs.forEach(job -> input.append(eJson.toJson(job)).append('\n'));
        final var captured = new ByteArrayOutputStream();
        final InputStream originalIn = System.in;
        final PrintStream originalOut = System.out;
        try {
            System.setIn(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Test262Worker.main();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank())
                .map(line -> eJson.fromJson(line, JsonObject.class)).toList();
    }

    private JsonObject result(String fixture, List<String> includes, List<String> flags, String negativeType) {
        return drive(List.of(job(fixture, includes, flags, negativeType))).getFirst();
    }

    private String status(JsonObject result) {
        return result.get("status").asJsonString().getValue();
    }

    private String message(JsonObject result) {
        return new String(Base64.getDecoder().decode(result.get("messageB64").asJsonString().getValue()),
                StandardCharsets.UTF_8);
    }

    @Test
    public void test_positive_test_passes() {
        final var result = result("pass-simple.js", List.of(), List.of(), "");
        assertEquals("PASS", status(result));
        assertEquals("pass-simple.js", result.get("id").asJsonString().getValue());
    }

    @Test
    public void test_assertion_failure_reports_test262error() {
        final var result = result("fail-assertion.js", List.of(), List.of(), "");
        assertEquals("FAIL", status(result));
        assertTrue(message(result).contains("deliberate fixture failure"), message(result));
    }

    @Test
    public void test_negative_runtime_type_match() {
        assertEquals("PASS", status(result("negative-runtime.js", List.of(), List.of(), "TypeError")));
    }

    @Test
    public void test_negative_parse_reports_syntaxerror() {
        assertEquals("PASS", status(result("negative-parse.js", List.of(), List.of(), "SyntaxError")));
    }

    @Test
    public void test_negative_type_mismatch_fails() {
        final var result = result("negative-mismatch.js", List.of(), List.of(), "RangeError");
        assertEquals("FAIL", status(result));
        assertTrue(message(result).contains("expected RangeError but got TypeError"), message(result));
    }

    @Test
    public void test_negative_without_error_fails() {
        final var result = result("pass-simple.js", List.of(), List.of(), "TypeError");
        assertEquals("FAIL", status(result));
        assertTrue(message(result).contains("but the test completed"), message(result));
    }

    @Test
    public void test_async_done_sentinel_passes() {
        assertEquals("PASS", status(result("async-done.js", List.of(), List.of("async"), "")));
    }

    @Test
    public void test_async_done_with_message_fails() {
        final var result = result("async-done-message.js", List.of(), List.of("async"), "");
        assertEquals("FAIL", status(result));
        assertTrue(message(result).contains("deliberate async failure"), message(result));
    }

    @Test
    public void test_async_without_done_fails() {
        final var result = result("pass-simple.js", List.of(), List.of("async"), "");
        assertEquals("FAIL", status(result));
        assertTrue(message(result).contains("$DONE"), message(result));
    }

    @Test
    public void test_includes_are_prepended_in_order() {
        assertEquals("PASS",
                status(result("includes-inline.js", List.of("fixtureFirst.js", "fixtureSecond.js"), List.of(), "")));
    }

    @Test
    public void test_strict_directive_prepended() {
        assertEquals("PASS", status(result("only-strict.js", List.of(), List.of("onlyStrict"), "")));
    }

    @Test
    public void test_raw_test_runs_without_prelude() {
        assertEquals("PASS", status(result("raw-no-prelude.js", List.of(), List.of("raw"), "")));
    }

    @Test
    public void test_message_base64_round_trip() {
        final var message = message(result("message-round-trip.js", List.of(), List.of(), ""));
        assertTrue(message.contains("\n"), message);
        assertTrue(message.contains("\"two\""), message);
    }

    @Test
    public void test_missing_file_is_a_failure() {
        assertEquals("FAIL", status(result("does-not-exist.js", List.of(), List.of(), "")));
    }

    @Test
    public void test_worker_uses_the_strict_script_goal(@TempDir Path dir) throws IOException {
        for (final var source : List.of("return;", "export default null;", "import.meta;", "using x = null;")) {
            final var path = Files.writeString(dir.resolve("goal.js"), source);
            final var job = job("goal.js", List.of(), List.of("raw"), "SyntaxError");
            job.addProperty("path", path.toString());
            assertEquals("PASS", status(drive(List.of(job)).getFirst()), source);
        }
    }

    @Test
    public void test_batch_of_jobs_answered_in_order() {
        final var results = drive(List.of(job("pass-simple.js", List.of(), List.of(), ""),
                job("fail-assertion.js", List.of(), List.of(), ""), job("pass-simple.js", List.of(), List.of(), "")));
        assertEquals(3, results.size());
        assertEquals(List.of("PASS", "FAIL", "PASS"), results.stream().map(this::status).toList());
    }
}
