package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class RegexSymbolMethodsTest {
    private static final long MEMORY_BUDGET = 4L * 1024 * 1024;

    private static ScriptResult run(String source) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), null, null,
                new ResourceLimits(-1, 30_000L, 200, MEMORY_BUDGET)));
    }

    private static String text(ScriptResult result) {
        assertFalse(result.isError(), result.getErrorName() + ": " + result.getErrorMessage());
        return result.getValue().asJsonString().getValue();
    }

    private static void assertOutOfMemory(ScriptResult result) {
        assertTrue(result.isError(), "expected the memory budget to refuse the run");
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_a_global_template_replace_does_not_retain_its_matches() {
        assertEquals("200000:200000", text(run("const s = 'a'.repeat(200000);"
                + " return s.replace(/a/g, 'b').length + ':' + s.replaceAll(/a/g, '$&').length;")));
        assertEquals("x-y-z", text(run("return 'x y z'.replace(/(?<sep> )/g, '-');")));
        assertEquals("[a][b]", text(run("return 'ab'.replace(/(?<c>.)/g, '[$<c>]');")));
    }

    @Test
    public void test_a_function_replace_still_charges_every_retained_match() {
        assertOutOfMemory(run("const s = 'a'.repeat(200000); return s.replace(/a/g, () => 'b').length;"));
        assertEquals("b0b1", text(run("return 'aa'.replace(/a/g, (m, i) => 'b' + i);")));
    }

    @Test
    public void test_a_custom_exec_keeps_every_exec_call_before_reading_the_results() {
        final var source = """
                const log = [];
                const rx = /a/g;
                let calls = 0;
                rx.exec = function () {
                    calls++;
                    log.push('exec');
                    if (calls > 2) {
                        return null;
                    }
                    const at = calls - 1;
                    return { length: 1, 0: 'a', get index() { log.push('index' + at); return at; } };
                };
                const out = RegExp.prototype[Symbol.replace].call(rx, 'aa', 'b');
                return out + ':' + log.join(',');
                """;
        assertEquals("bb:exec,exec,exec,index0,index1", text(run(source)));
    }

    @Test
    public void test_encode_uri_component_is_refused_when_its_output_exceeds_the_budget() {
        assertOutOfMemory(run("return encodeURIComponent('é'.repeat(300000)).length;"));
        assertEquals("a%E2%82%AC%F0%9D%84%9E%C3%A9%20/",
                text(run("return encodeURIComponent('a€\ud834\udd1eé ') + '/';")));
        assertEquals("a%E2%82%AC/?", text(run("return encodeURI('a€/?');")));
    }

    @Test
    public void test_escape_is_refused_when_its_output_exceeds_the_budget() {
        assertOutOfMemory(run("return escape('€'.repeat(300000)).length;"));
        assertEquals("a%E9%u20AC@*_+-./", text(run("return escape('aé€@*_+-./');")));
    }

    @Test
    public void test_json_stringify_charges_its_escaped_rendering() {
        assertOutOfMemory(run("return JSON.stringify('\\u0001'.repeat(300000)).length;"));
        assertEquals("{\"\\u0001\":\"\\n\\\"\"}", text(run("return JSON.stringify({ '\\u0001': '\\n\"' });")));
    }
}
