package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.unit.simplejs.internal.regex.RegexCorpus;

public class RegexTestMethodTest {
    private static final String[] FLAGS = {"", "g", "y", "gy"};
    private static final int CALLS = 100;

    private final SimpleJs engine = new SimpleJs();

    private record LimitedBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
            ResourceLimits limits, CancellationToken cancellation) implements HostBindings {
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String literal(String text) {
        final var out = new StringBuilder("'");
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '\\' || c == '\'') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private long instructions(String source) {
        final var result = engine.run(source,
                new LimitedBindings(new JsonObject(), null, null, new ResourceLimits(-1, -1, 100), null));
        assertFalse(result.isError(), result.getErrorMessage());
        return result.getMetrics().instructions();
    }

    @Test
    public void test_test_agrees_with_exec_and_moves_last_index_the_same_way() {
        for (final var pattern : RegexCorpus.PATTERNS) {
            for (final var flag : FLAGS) {
                final var flags = literal(pattern[1] + flag);
                final var source = literal(pattern[0]);
                for (final var input : RegexCorpus.INPUTS) {
                    final var script = "const a = new RegExp(" + source + ", " + flags + "), b = new RegExp(" + source
                            + ", " + flags + "); const s = " + literal(input) + "; let same = true;"
                            + " for (let k = 0; k < 4; k++) { same = same && a.test(s) === (b.exec(s) !== null)"
                            + " && a.lastIndex === b.lastIndex; } same";
                    assertTrue(bool(script), pattern[0] + " /" + flag + " on " + input);
                }
            }
        }
    }

    @Test
    public void test_a_user_defined_exec_is_still_called() {
        assertEquals("false:1:a",
                str("const r = /a/; let calls = 0, seen = '';"
                        + " r.exec = function (s) { calls++; seen = s; return null; };"
                        + " r.test('a') + ':' + calls + ':' + seen"));
        assertTrue(bool("const r = /x/; r.exec = () => ({}); r.test('a')"));
        assertEquals("true:1",
                str("let calls = 0; class R extends RegExp { exec(s) { calls++; return super.exec(s); } }"
                        + " new R('b+').test('abbb') + ':' + calls"));
        assertEquals("false:2",
                str("let calls = 0; const original = RegExp.prototype.exec;"
                        + " RegExp.prototype.exec = function (s) { calls++; return original.call(this, s) && null; };"
                        + " /a/.test('a') + ':' + (/b/.test('b') ? 1 : calls)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const r = /a/; r.exec = () => 1; r.test('a')"));
    }

    @Test
    public void test_the_exec_lookup_stays_observable_on_the_builtin_path() {
        assertEquals("true:1",
                str("const r = /a/; let reads = 0;"
                        + " Object.defineProperty(r, 'exec', { get() { reads++; return RegExp.prototype.exec; } });"
                        + " r.test('a') + ':' + reads"));
        assertEquals("true:2",
                str("const r = /a/g; let reads = 0;" + " r.lastIndex = { valueOf() { reads++; return 0; } };"
                        + " const first = r.test('aa'); r.lastIndex = { valueOf() { reads++; return 1; } };"
                        + " (first && r.test('aa')) + ':' + reads"));
    }

    @Test
    public void test_last_index_updates_for_global_and_sticky_regexes() {
        assertEquals("true:3,true:7,false:0",
                str("const r = /b+/g; const s = 'abbabbb';"
                        + " const out = []; for (let k = 0; k < 3; k++) { out.push(r.test(s) + ':' + r.lastIndex); }"
                        + " out.join(',')"));
        assertEquals("false:0,true:2",
                str("const r = /a/y; const out = []; r.lastIndex = 0;"
                        + " out.push(r.test('ba') + ':' + r.lastIndex); r.lastIndex = 1;"
                        + " out.push(r.test('ba') + ':' + r.lastIndex); out.join(',')"));
        assertEquals("false:0", str("const r = /a/g; r.lastIndex = 9; r.test('aa') + ':' + r.lastIndex"));
        assertEquals("true:7", str("const r = /a/; r.lastIndex = 7; r.test('aa') + ':' + r.lastIndex"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("const r = /a/g; Object.defineProperty(r, 'lastIndex', { writable: false }); r.test('a')"));
    }

    @Test
    public void test_a_receiver_that_is_not_a_regexp_is_still_refused() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("RegExp.prototype.test.call({ exec: () => ({}) }, 'a')"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("RegExp.prototype.test.call({ exec: RegExp.prototype.exec }, 'a')"));
    }

    @Test
    public void test_the_instruction_budget_is_charged_as_for_two_exec_calls() {
        final var loop = "const r = /ab+c/g; let c = 0; for (let i = 0; i < " + CALLS + "; i++) { if (";
        final var viaTest = instructions(loop + "r.test('xxabbbcxx')) c++; } return c;");
        final var viaExec = instructions(loop + "r.exec('xxabbbcxx') !== null) c++; } return c;");
        assertEquals(2L * CALLS, viaTest - viaExec);
        final var heavyTest = instructions("return /(x+x+)+y/.test('x'.repeat(20) + '!');");
        final var heavyExec = instructions("return /(x+x+)+y/.exec('x'.repeat(20) + '!') !== null;");
        assertEquals(2L, heavyTest - heavyExec);
    }
}
