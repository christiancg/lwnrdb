package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

/**
 * Pins the invariant the compiled-procedure cache rests on: a parsed program holds no per-run state, so one
 * CompiledScript can back every call of a stored procedure.
 */
public class CompiledScriptReuseTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static SimpleHostBindings hostWith(String argName, String argValue) {
        final var args = new JsonObject();
        if (argName != null) {
            args.add(argName, new JsonString(argValue));
        }
        return new SimpleHostBindings(args, null, null, ResourceLimits.unlimited());
    }

    @Test
    public void test_same_compiled_script_run_twice_gives_same_result() {
        final var compiled = simpleJs.compile("import args from 'args'; return args.name + '!';", false);
        final var first = simpleJs.run(compiled, hostWith("name", "alice"));
        final var second = simpleJs.run(compiled, hostWith("name", "bob"));
        assertFalse(first.isError(), first.getErrorMessage());
        assertFalse(second.isError(), second.getErrorMessage());
        assertEquals("alice!", first.getValue().asJsonString().getValue());
        assertEquals("bob!", second.getValue().asJsonString().getValue());
    }

    // A regex literal keeps its pattern text, not a compiled object with a mutable lastIndex
    @Test
    public void test_regex_last_index_does_not_leak_between_runs() {
        final var compiled = simpleJs.compile(
                "const re = /a/g; let count = 0; while (re.exec('aaa') !== null) { count++; } return count;", false);
        final var first = simpleJs.run(compiled, hostWith(null, null));
        final var second = simpleJs.run(compiled, hostWith(null, null));
        assertEquals(3d, first.getValue().asJsonNumber().getValue().doubleValue());
        assertEquals(3d, second.getValue().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_top_level_var_does_not_leak_between_runs() {
        final var compiled = simpleJs.compile("var seen = (typeof seen === 'undefined') ? 1 : seen + 1; return seen;",
                false);
        assertEquals(1d,
                simpleJs.run(compiled, hostWith(null, null)).getValue().asJsonNumber().getValue().doubleValue());
        assertEquals(1d,
                simpleJs.run(compiled, hostWith(null, null)).getValue().asJsonNumber().getValue().doubleValue());
    }

    // A class declaration installs methods on a fresh prototype each run
    @Test
    public void test_class_state_does_not_leak_between_runs() {
        final var compiled = simpleJs.compile(
                "class C { static count = 0; static bump() { C.count++; return C.count; } } return C.bump();", false);
        assertEquals(1d,
                simpleJs.run(compiled, hostWith(null, null)).getValue().asJsonNumber().getValue().doubleValue());
        assertEquals(1d,
                simpleJs.run(compiled, hostWith(null, null)).getValue().asJsonNumber().getValue().doubleValue());
    }

    // compile() throws so a caller can refuse to persist an unparseable procedure
    @Test
    public void test_compile_rejects_syntax_error_by_throwing() {
        assertThrows(UnexpectedTokenException.class, () -> simpleJs.compile("return (;", false));
    }

    // ...while the source overload keeps its contract of reporting it as a result
    @Test
    public void test_run_string_still_returns_syntax_error_result() {
        final var result = simpleJs.run("return (;", hostWith(null, null));
        assertTrue(result.isError());
        assertEquals("SyntaxError", result.getErrorName());
    }

    @Test
    public void test_compiled_script_exposes_its_source_and_hash() {
        final var compiled = simpleJs.compile("return 1;", false);
        assertEquals("return 1;", compiled.source());
        assertFalse(compiled.strictScriptGoal());
        assertEquals(64, compiled.sourceHash().length());
        assertEquals(compiled.sourceHash(), simpleJs.compile("return 1;", false).sourceHash());
    }

    // A program parsed under the other goal is the wrong program, so it is parsed again rather than run
    @Test
    public void test_run_with_mismatched_script_goal_reparses() {
        final var compiled = simpleJs.compile("return 41 + 1;", false);
        final var strictHost = new SimpleHostBindings(new JsonObject(), null, null,
                new ResourceLimits(-1, -1, -1, true, true));
        final var result = simpleJs.run(compiled, strictHost);
        // A top-level return is an early error under the Script goal, so re-parsing is observable.
        assertTrue(result.isError());
        assertEquals("SyntaxError", result.getErrorName());
    }
}
