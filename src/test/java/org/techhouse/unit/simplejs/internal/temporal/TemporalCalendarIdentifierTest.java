package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    public void test_require_builtin_calendar_accepts_builtin() {
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendar("iso8601"));
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendar("ISO8601"));
    }

    @Test
    public void test_require_builtin_calendar_rejects_unknown_calendar() {
        assertThrows(RangeErrorException.class, () -> TemporalCalendarIdentifier.requireBuiltinCalendar("hebrew"));
    }

    // Unlike requireBuiltinCalendarOrAnnotated, requireBuiltinCalendar does not extract a u-ca annotation
    // from a full ISO string.
    @Test
    public void test_require_builtin_calendar_rejects_full_iso_string() {
        assertThrows(RangeErrorException.class,
                () -> TemporalCalendarIdentifier.requireBuiltinCalendar("2023-11-30[u-ca=iso8601]"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_accepts_bare_builtin() {
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("iso8601"));
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("ISO8601"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_extracts_annotation_from_date_string() {
        assertDoesNotThrow(
                () -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("2023-11-30[u-ca=iso8601]"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_defaults_missing_annotation_on_date_string() {
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("2023-11-30"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_extracts_annotation_from_year_month_string() {
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("2023-11[u-ca=iso8601]"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_extracts_annotation_from_month_day_string() {
        assertDoesNotThrow(() -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("--11-30[u-ca=iso8601]"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_extracts_annotation_from_time_string() {
        assertDoesNotThrow(
                () -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("12:34:56[u-ca=iso8601]"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_rejects_non_iso8601_annotation() {
        assertThrows(RangeErrorException.class,
                () -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("2023-11-30[u-ca=hebrew]"));
    }

    @Test
    public void test_require_builtin_calendar_or_annotated_rejects_unparseable_string() {
        assertThrows(RangeErrorException.class,
                () -> TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated("not-a-date"));
    }
}
