package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

/**
 * The locales/options arguments the toLocaleString / localeCompare family accepts. Exercised through the
 * engine rather than against the resolver directly, since a JsValue argument list is what it is built for.
 */
public class LocaleResolverTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    // The assertion that proves the argument is read at all: Swedish sorts "ä" after "z", English does not.
    @Test
    public void test_localecompare_honours_the_requested_locale() {
        assertEquals(1, run("return 'ä'.localeCompare('z', 'sv');").getValue().asJsonNumber().asInteger());
        assertEquals(-1, run("return 'ä'.localeCompare('z', 'en');").getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_an_array_of_locales_takes_the_first() {
        assertEquals(1, run("return 'ä'.localeCompare('z', ['sv', 'en']);").getValue().asJsonNumber().asInteger());
        assertEquals(-1, run("return 'ä'.localeCompare('z', ['en', 'sv']);").getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_base_sensitivity_ignores_accents() {
        assertEquals(0, run("return 'a'.localeCompare('á', 'en', { sensitivity: 'base' });").getValue().asJsonNumber()
                .asInteger());
        assertTrue(run("return 'a'.localeCompare('á', 'en');").getValue().asJsonNumber().asInteger() != 0);
    }

    @Test
    public void test_an_undefined_locale_still_uses_the_host_locale() {
        assertEquals(-1, run("return 'a'.localeCompare('b');").getValue().asJsonNumber().asInteger());
        assertEquals(-1, run("return 'a'.localeCompare('b', undefined);").getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_a_structurally_invalid_tag_is_a_range_error() {
        final var result = run("return 'a'.localeCompare('b', '!');");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    @Test
    public void test_an_unknown_option_value_is_a_range_error() {
        final var result = run("return 'a'.localeCompare('b', 'en', { sensitivity: 'sideways' });");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    @Test
    public void test_a_non_object_options_argument_is_a_type_error() {
        final var result = run("return 'a'.localeCompare('b', 'en', 7);");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_number_tolocalestring_honours_the_requested_locale() {
        assertEquals("1,234.5", run("return (1234.5).toLocaleString('en-US');").getValue().asJsonString().getValue());
        assertEquals("1.234,5", run("return (1234.5).toLocaleString('de-DE');").getValue().asJsonString().getValue());
    }

    @Test
    public void test_date_tolocalestring_honours_the_requested_locale() {
        final var us = run("return new Date(0).toLocaleDateString('en-US');").getValue().asJsonString().getValue();
        final var de = run("return new Date(0).toLocaleDateString('de-DE');").getValue().asJsonString().getValue();
        assertNotEquals(us, de, "expected the two locales to render differently, both were " + us);
    }

    @Test
    public void test_an_empty_locale_array_falls_back_to_the_host_locale() {
        assertEquals(-1, run("return 'a'.localeCompare('b', []);").getValue().asJsonNumber().asInteger());
        assertEquals(-1, run("return 'a'.localeCompare('b', [undefined]);").getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_null_options_is_a_type_error_but_undefined_is_not() {
        assertTrue(run("return 'a'.localeCompare('b', 'en', null);").isError());
        assertEquals(-1, run("return 'a'.localeCompare('b', 'en', undefined);").getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_an_absent_option_key_is_ignored() {
        assertEquals(-1,
                run("return 'a'.localeCompare('b', 'en', { other: 1 });").getValue().asJsonNumber().asInteger());
        assertEquals(-1, run("return 'a'.localeCompare('b', 'en', { sensitivity: undefined });").getValue()
                .asJsonNumber().asInteger());
    }

    // usage and caseFirst are validated even though java.text cannot honour them, so a typo is an error
    // rather than a silent no-op.
    @Test
    public void test_unhonoured_options_are_still_validated() {
        assertEquals(-1,
                run("return 'a'.localeCompare('b', 'en', { usage: 'search' });").getValue().asJsonNumber().asInteger());
        assertTrue(run("return 'a'.localeCompare('b', 'en', { usage: 'sideways' });").isError());
        assertTrue(run("return 'a'.localeCompare('b', 'en', { caseFirst: 'sideways' });").isError());
    }
}
