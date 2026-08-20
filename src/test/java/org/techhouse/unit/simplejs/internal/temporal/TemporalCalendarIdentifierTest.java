package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;

public class TemporalCalendarIdentifierTest {
    @Test
    public void test_is_builtin_exact_match() {
        assertTrue(TemporalCalendarIdentifier.isBuiltin("iso8601"));
    }

    @Test
    public void test_is_builtin_case_insensitive() {
        assertTrue(TemporalCalendarIdentifier.isBuiltin("ISO8601"));
        assertTrue(TemporalCalendarIdentifier.isBuiltin("Iso8601"));
    }

    @Test
    public void test_is_builtin_rejects_unknown_calendar() {
        assertFalse(TemporalCalendarIdentifier.isBuiltin("hebrew"));
    }

    @Test
    public void test_is_builtin_rejects_different_length() {
        assertFalse(TemporalCalendarIdentifier.isBuiltin("iso860"));
        assertFalse(TemporalCalendarIdentifier.isBuiltin("iso86011"));
    }

    @Test
    public void test_ascii_equals_ignore_case_matches_exact() {
        assertTrue(TemporalCalendarIdentifier.asciiEqualsIgnoreCase("utc", "utc"));
    }

    @Test
    public void test_ascii_equals_ignore_case_matches_mixed_case() {
        assertTrue(TemporalCalendarIdentifier.asciiEqualsIgnoreCase("UtC", "utc"));
        assertTrue(TemporalCalendarIdentifier.asciiEqualsIgnoreCase("UTC", "utc"));
    }

    @Test
    public void test_ascii_equals_ignore_case_rejects_different_length() {
        assertFalse(TemporalCalendarIdentifier.asciiEqualsIgnoreCase("ut", "utc"));
    }

    @Test
    public void test_ascii_equals_ignore_case_rejects_mismatch() {
        assertFalse(TemporalCalendarIdentifier.asciiEqualsIgnoreCase("utd", "utc"));
    }

    @Test
    public void test_canonicalize_bare_accepts_builtin() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeBare("iso8601"));
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeBare("ISO8601"));
    }

    @Test
    public void test_canonicalize_bare_rejects_unknown_calendar() {
        assertThrows(RangeErrorException.class, () -> TemporalCalendarIdentifier.canonicalizeBare("hebrew"));
    }

    // canonicalizeBare does not extract a u-ca annotation from a full ISO string, unlike
    // canonicalizeFlexible - a validly-formed date string is still rejected here.
    @Test
    public void test_canonicalize_bare_rejects_full_iso_string() {
        assertThrows(RangeErrorException.class,
                () -> TemporalCalendarIdentifier.canonicalizeBare("2023-11-30[u-ca=iso8601]"));
    }

    @Test
    public void test_canonicalize_flexible_accepts_bare_builtin() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("iso8601"));
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("ISO8601"));
    }

    @Test
    public void test_canonicalize_flexible_extracts_annotation_from_date_string() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("2023-11-30[u-ca=iso8601]"));
    }

    @Test
    public void test_canonicalize_flexible_defaults_missing_annotation_on_date_string() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("2023-11-30"));
    }

    @Test
    public void test_canonicalize_flexible_extracts_annotation_from_year_month_string() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("2023-11[u-ca=iso8601]"));
    }

    @Test
    public void test_canonicalize_flexible_extracts_annotation_from_month_day_string() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("--11-30[u-ca=iso8601]"));
    }

    @Test
    public void test_canonicalize_flexible_extracts_annotation_from_time_string() {
        assertEquals("iso8601", TemporalCalendarIdentifier.canonicalizeFlexible("12:34:56[u-ca=iso8601]"));
    }

    @Test
    public void test_canonicalize_flexible_rejects_non_iso8601_annotation() {
        assertThrows(RangeErrorException.class,
                () -> TemporalCalendarIdentifier.canonicalizeFlexible("2023-11-30[u-ca=hebrew]"));
    }

    @Test
    public void test_canonicalize_flexible_rejects_unparseable_string() {
        assertThrows(RangeErrorException.class, () -> TemporalCalendarIdentifier.canonicalizeFlexible("not-a-date"));
    }
}
