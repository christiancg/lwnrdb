package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.builtins.LocaleResolver;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.JsString;

public class LocaleResolverTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    private static Locale resolve(String tag) {
        return LocaleResolver.resolve(List.of(new JsString(tag)), 0, null);
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
        assertNotEquals(0, run("return 'a'.localeCompare('á', 'en');").getValue().asJsonNumber().asInteger());
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

    @Test
    public void test_unhonoured_options_are_still_validated() {
        assertEquals(-1,
                run("return 'a'.localeCompare('b', 'en', { usage: 'search' });").getValue().asJsonNumber().asInteger());
        assertTrue(run("return 'a'.localeCompare('b', 'en', { usage: 'sideways' });").isError());
        assertTrue(run("return 'a'.localeCompare('b', 'en', { caseFirst: 'sideways' });").isError());
    }

    @Test
    public void test_private_use_tags_resolve_to_the_base_locale() {
        assertEquals("1,234.5",
                run("return (1234.5).toLocaleString('en-x-abc');").getValue().asJsonString().getValue());
        assertEquals("1.234,5",
                run("return (1234.5).toLocaleString('de-DE-x-abc');").getValue().asJsonString().getValue());
    }

    @Test
    public void test_resolved_locales_come_from_the_available_set() {
        final var available = new HashSet<>(List.of(Locale.getAvailableLocales()));
        final var distinct = new HashSet<Locale>();
        for (var i = 0; i < 10_000; i++) {
            distinct.add(resolve("en-x-" + Integer.toString(i, 36)));
            distinct.add(resolve("en-US-x-u" + Integer.toString(i, 36)));
        }
        assertTrue(available.containsAll(distinct), "resolved outside the available set: " + distinct);
        assertTrue(distinct.size() <= 2, "expected en and en-US only, got " + distinct);
    }

    @Test
    public void test_unknown_languages_fall_back_to_the_host_locale() {
        final var host = run("return (1234.5).toLocaleString();").getValue().asJsonString().getValue();
        assertEquals(host, run("return (1234.5).toLocaleString('qqq');").getValue().asJsonString().getValue());
        assertEquals(host, run("return (1234.5).toLocaleString('abcdefgh');").getValue().asJsonString().getValue());
    }

    @Test
    public void test_a_malformed_tag_is_a_range_error() {
        for (final var tag : new String[]{"--", "en_US@@", "", "und", "x-private"}) {
            final var result = run("return (1).toLocaleString('" + tag + "');");
            assertTrue(result.isError(), "expected a RangeError for '" + tag + "'");
            assertEquals("RangeError", result.getErrorName(), tag);
        }
    }

    @Test
    public void test_real_locales_format_as_before() {
        for (final var tag : new String[]{"de-DE", "ja-JP", "zh-Hant-TW", "ar-EG", "en-US", "fr"}) {
            final var expected = NumberFormat.getInstance(Locale.forLanguageTag(tag)).format(1234567.891);
            final var actual = run("return (1234567.891).toLocaleString('" + tag + "');").getValue().asJsonString()
                    .getValue();
            assertEquals(expected, actual, tag);
        }
        assertEquals(1, run("return 'ä'.localeCompare('z', 'sv-SE');").getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_case_is_normalised_before_the_lookup() {
        assertEquals("1.234,5", run("return (1234.5).toLocaleString('DE-de');").getValue().asJsonString().getValue());
        assertEquals("1,234.5",
                run("return (1234.5).toLocaleString('zh-hant-tw');").getValue().asJsonString().getValue());
    }
}
