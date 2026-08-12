package org.techhouse.unit.simplejs.test262;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

// Job-per-line worker JVM for the test262 harness: reads one EJson job from stdin, assembles the
// prelude + test body itself (only paths cross the pipe), runs it through SimpleJs and writes one
// EJson result line. Driven by test_utils/test262.py, which owns batching, timeouts and the gate.
// Deliberately not a JUnit test class — there is nothing here for surefire to run.
public final class Test262Worker {
    // maxDepth must stay at or below 1000: at 2000 a deeply recursive test blows the JVM stack on the
    // engine's virtual thread before the depth guard fires and SimpleJs.run never returns.
    private static final ResourceLimits LIMITS = new ResourceLimits(50_000_000L, 5_000L, 1_000);
    private static final String ASYNC_PASS = "Test262:AsyncTestComplete";
    private static final String ASYNC_FAIL_PREFIX = "Test262:AsyncTestFailure:";
    private static final List<String> PRELUDE_SHIMS = List.of("print.js", "host262.js");
    // $DONE is defined only for async tests: a non-async test asserts globalThis has no own "$DONE".
    private static final String ASYNC_SHIM = "done.js";
    private static final List<String> PRELUDE_HARNESS = List.of("sta.js", "assert.js");

    private Test262Worker() {
    }

    public static void main(String[] args) throws IOException {
        final var eJson = new EJson();
        try (var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final var job = eJson.fromJson(line, JsonObject.class);
                System.out.println(eJson.toJson(runJob(job)));
                System.out.flush();
            }
        }
    }

    private static JsonObject runJob(JsonObject job) {
        final var id = string(job, "id");
        try {
            return execute(job, id);
        } catch (RuntimeException | IOException | StackOverflowError error) {
            return result(id, "FAIL", error.getClass().getSimpleName(), String.valueOf(error.getMessage()));
        }
    }

    private static JsonObject execute(JsonObject job, String id) throws IOException {
        final var flags = strings(job, "flags");
        final var source = assemble(job, flags);
        final var console = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, console::add, LIMITS);
        final var outcome = new SimpleJs().run(source, host);
        final var negativeType = string(job, "negativeType");
        if (!negativeType.isEmpty()) {
            return negativeVerdict(id, negativeType, outcome);
        }
        if (outcome.isError()) {
            return result(id, "FAIL", outcome.getErrorName(), outcome.getErrorMessage());
        }
        if (flags.contains("async")) {
            return asyncVerdict(id, console);
        }
        return result(id, "PASS", "", "");
    }

    private static JsonObject negativeVerdict(String id, String negativeType, ScriptResult outcome) {
        if (!outcome.isError()) {
            return result(id, "FAIL", "", "expected " + negativeType + " but the test completed");
        }
        if (negativeType.equals(outcome.getErrorName())) {
            return result(id, "PASS", outcome.getErrorName(), outcome.getErrorMessage());
        }
        return result(id, "FAIL", outcome.getErrorName(),
                "expected " + negativeType + " but got " + outcome.getErrorName() + ": " + outcome.getErrorMessage());
    }

    private static JsonObject asyncVerdict(String id, List<String> console) {
        for (final var printed : console) {
            if (ASYNC_PASS.equals(printed.strip())) {
                return result(id, "PASS", "", "");
            }
            if (printed.startsWith(ASYNC_FAIL_PREFIX)) {
                return result(id, "FAIL", "Test262AsyncFailure", printed.substring(ASYNC_FAIL_PREFIX.length()).strip());
            }
        }
        return result(id, "FAIL", "", "$DONE was never called with a passing sentinel");
    }

    // The prelude is byte-identical across every non-raw test, so a prelude defect shows up as a
    // global failure rather than a flaky subset.
    private static String assemble(JsonObject job, List<String> flags) throws IOException {
        final var body = read(Path.of(string(job, "path")));
        if (flags.contains("raw")) {
            return body;
        }
        final var source = new StringBuilder("\"use strict\";\n");
        final var shimDir = Path.of(string(job, "shimDir"));
        final var harnessDir = Path.of(string(job, "harnessDir"));
        for (final var shim : PRELUDE_SHIMS) {
            source.append(read(shimDir.resolve(shim))).append('\n');
        }
        if (flags.contains("async")) {
            source.append(read(shimDir.resolve(ASYNC_SHIM))).append('\n');
        }
        for (final var harness : PRELUDE_HARNESS) {
            source.append(read(harnessDir.resolve(harness))).append('\n');
        }
        for (final var include : strings(job, "includes")) {
            if (!PRELUDE_HARNESS.contains(include)) {
                source.append(read(harnessDir.resolve(include))).append('\n');
            }
        }
        return source.append(body).toString();
    }

    // Corpus files are not guaranteed to be well-formed UTF-8, so decode replacing bad input rather
    // than failing the test on its encoding.
    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static JsonObject result(String id, String status, String errorName, String message) {
        final var result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("status", status);
        result.addProperty("errorName", errorName == null ? "" : errorName);
        result.addProperty("messageB64",
                Base64.getEncoder().encodeToString((message == null ? "" : message).getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    private static String string(JsonObject job, String key) {
        final var value = job.get(key);
        return value == null || value.isJsonNull() ? "" : value.asJsonString().getValue();
    }

    private static List<String> strings(JsonObject job, String key) {
        final var value = job.get(key);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        final var values = new ArrayList<String>();
        for (final var element : (JsonArray) value) {
            values.add(element.asJsonString().getValue());
        }
        return values;
    }
}
