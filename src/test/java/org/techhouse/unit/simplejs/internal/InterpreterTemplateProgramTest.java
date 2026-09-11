package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class InterpreterTemplateProgramTest {
    private static String str() {
        return ((JsString) Interpreter.run("let s = '';\nfor (let i = 0; i < 3; i++) {\n    s += i;\n}\ns\n"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // A loop builds a string with the += operator
    @Test
    public void test_string_building() {
        assertEquals("012", str());
    }

    // A regex literal drives a global replace end to end
    @Test
    public void test_regex_global_replace() {
        assertEquals("a#b#", str("'a1b2'.replace(/\\d/g, '#')"));
    }

    // Named capture groups are read from a match result
    @Test
    public void test_regex_named_capture() {
        final var source = """
                const m = '2024-01'.match(/(?<year>\\d+)-(?<month>\\d+)/);
                m.groups.year + '/' + m.groups.month
                """;
        assertEquals("2024/01", str(source));
    }

    // A tagged template invokes the tag with the strings array followed by the interpolated values
    @Test
    public void test_tagged_template_passes_strings_and_values() {
        final var source = """
                function t(s, ...v) { return s.join('|') + '#' + v.join(','); }
                t`a${1}b${2}c`
                """;
        assertEquals("a|b|c#1,2", str(source));
    }

    // The strings array carries a raw companion that preserves escape sequences
    @Test
    public void test_tagged_template_raw_property() {
        final var source = """
                function t(s) { return s.raw[0] + '/' + s[0]; }
                t`\\n`
                """;
        assertEquals("\\n/\n", str(source));
    }

    // A member-tagged template binds this to the receiver object
    @Test
    public void test_tagged_template_this_binding() {
        final var source = """
                const obj = { name: 'x', tag: function(s) { return this.name; } };
                obj.tag`hi`
                """;
        assertEquals("x", str(source));
    }

    // A template with no substitutions passes a single-element strings array and no extra args
    @Test
    public void test_tagged_template_no_substitutions() {
        final var source = """
                function t(s, ...v) { return s.length + ':' + v.length; }
                t`hello`
                """;
        assertEquals("1:0", str(source));
    }

    // A nested tagged template inside an interpolation evaluates correctly
    @Test
    public void test_tagged_template_nested() {
        final var source = """
                function u(s) { return s[0].toUpperCase(); }
                function t(s, v) { return s[0] + v + s[1]; }
                t`<${ u`x` }>`
                """;
        assertEquals("<X>", str(source));
    }

    // Tagging a non-function value throws a TypeError
    @Test
    public void test_tagged_template_non_function_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    const notFn = 5;
                    notFn`x`;
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // String.raw builds a string from the raw quasis and substitutions
    @Test
    public void test_string_raw_tag() {
        assertEquals("a\\n1b", str("String.raw`a\\n${1}b`"));
    }

    // GetTemplateObject caches the strings array by call-site (parse node) identity: evaluating the
    // same tagged template twice - even across different invocations of the enclosing function -
    // yields the very same array object both times.
    @Test
    public void test_tagged_template_same_site_is_cached() {
        final var source = """
                let first = null;
                let second = null;
                function t(s) { return s; }
                function run(sink) { sink(t`x${1}y`); }
                run(v => first = v);
                run(v => second = v);
                first === second ? 'true' : 'false'
                """;
        assertEquals("true", str(source));
    }

    // A textually different call site never shares the cached array, even when its cooked/raw
    // content happens to coincide with another site's.
    @Test
    public void test_tagged_template_different_site_is_not_cached() {
        final var source = """
                function t(s) { return s; }
                const a = t`x`;
                const b = t`x`;
                a === b ? 'true' : 'false'
                """;
        assertEquals("false", str(source));
    }

    // The "raw" companion is a non-enumerable, non-writable, non-configurable own property, so it
    // is absent from Object.keys/JSON.stringify and a plain write to it is silently rejected.
    @Test
    public void test_tagged_template_raw_is_non_enumerable() {
        final var source = """
                function t(s) { return s; }
                const strings = t`a${1}b`;
                const descriptor = Object.getOwnPropertyDescriptor(strings, 'raw');
                JSON.stringify([
                    Object.keys(strings).includes('raw'),
                    descriptor.enumerable,
                    descriptor.writable,
                    descriptor.configurable,
                ])
                """;
        assertEquals("[false,false,false,false]", str(source));
    }
}
