package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalParser;

public class TemporalParserTest {
    @Test
    public void test_parse_date() {
        final var result = TemporalParser.parseDate("2023-11-30");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
        assertNull(result.time());
    }

    @Test
    public void test_parse_date_with_calendar_annotation() {
        final var result = TemporalParser.parseDate("2023-11-30[u-ca=iso8601]");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
        assertEquals("iso8601", result.calendar());
    }

    @Test
    public void test_parse_date_with_trailing_time_and_offset_ignored_for_date_only() {
        final var result = TemporalParser.parseDate("2023-11-30T12:34:56.789-08:00");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
        assertEquals(new IsoTimeFields(12, 34, 56, 789, 0, 0), result.time());
    }

    @Test
    public void test_parse_date_expanded_year() {
        final var positive = TemporalParser.parseDate("+002023-11-30");
        assertEquals(new Iso8601Fields(2023, 11, 30), positive.date());
        final var negative = TemporalParser.parseDate("-000001-01-01");
        assertEquals(new Iso8601Fields(-1, 1, 1), negative.date());
    }

    @Test
    public void test_parse_date_rejects_invalid_calendar_date() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-02-29"));
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-13-01"));
    }

    @Test
    public void test_parse_date_accepts_leap_year_feb_29() {
        final var result = TemporalParser.parseDate("2024-02-29");
        assertEquals(new Iso8601Fields(2024, 2, 29), result.date());
    }

    @Test
    public void test_parse_time() {
        final var result = TemporalParser.parseTime("12:34:56.789123456");
        assertEquals(new IsoTimeFields(12, 34, 56, 789, 123, 456), result.time());
    }

    @Test
    public void test_parse_time_minimal() {
        final var result = TemporalParser.parseTime("09");
        assertEquals(new IsoTimeFields(9, 0, 0, 0, 0, 0), result.time());
    }

    @Test
    public void test_parse_time_clamps_leap_second() {
        final var result = TemporalParser.parseTime("23:59:60");
        assertEquals(59, result.time().second());
    }

    @Test
    public void test_parse_time_rejects_invalid_hour() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("24:00:00"));
    }

    @Test
    public void test_parse_date_time_with_t_separator() {
        final var result = TemporalParser.parseDateTime("2023-11-30T12:34:56");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
        assertEquals(new IsoTimeFields(12, 34, 56, 0, 0, 0), result.time());
    }

    @Test
    public void test_parse_date_time_with_space_separator() {
        final var result = TemporalParser.parseDateTime("2023-11-30 12:34:56");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
        assertEquals(new IsoTimeFields(12, 34, 56, 0, 0, 0), result.time());
    }

    @Test
    public void test_parse_date_time_requires_time_part() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDateTime("2023-11-30"));
    }

    @Test
    public void test_parse_instant_with_zulu() {
        final var result = TemporalParser.parseInstant("2023-11-30T12:34:56Z");
        assertEquals("Z", result.offset());
    }

    @Test
    public void test_parse_instant_with_offset() {
        final var result = TemporalParser.parseInstant("2023-11-30T12:34:56-08:00");
        assertEquals("-08:00", result.offset());
    }

    @Test
    public void test_parse_instant_requires_offset() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseInstant("2023-11-30T12:34:56"));
    }

    @Test
    public void test_parse_zoned_date_time() {
        final var result = TemporalParser.parseZonedDateTime("2023-11-30T12:34:56-08:00[America/Los_Angeles]");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
        assertEquals("-08:00", result.offset());
        assertEquals("America/Los_Angeles", result.timeZoneId());
    }

    @Test
    public void test_parse_zoned_date_time_with_calendar_annotation() {
        final var result = TemporalParser
                .parseZonedDateTime("2023-11-30T12:34:56-08:00[America/Los_Angeles][u-ca=iso8601]");
        assertEquals("America/Los_Angeles", result.timeZoneId());
        assertEquals("iso8601", result.calendar());
    }

    @Test
    public void test_parse_zoned_date_time_requires_time_zone_bracket() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseZonedDateTime("2023-11-30T12:34:56-08:00"));
    }

    @Test
    public void test_parse_time_zone_identifier_offset() {
        assertEquals("+01:00", TemporalParser.parseTimeZoneIdentifier("+01:00"));
    }

    @Test
    public void test_parse_time_zone_identifier_iana_name() {
        assertEquals("America/Los_Angeles", TemporalParser.parseTimeZoneIdentifier("America/Los_Angeles"));
    }

    @Test
    public void test_unknown_critical_annotation_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-11-30[!foo=bar]"));
    }

    @Test
    public void test_unknown_non_critical_annotation_ignored() {
        final var result = TemporalParser.parseDate("2023-11-30[foo=bar]");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
    }

    @Test
    public void test_bare_time_zone_annotation_must_be_first() {
        assertThrows(RangeErrorException.class,
                () -> TemporalParser.parseDate("2023-11-30[u-ca=iso8601][America/Los_Angeles]"));
    }

    @Test
    public void test_trailing_characters_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-11-30extra"));
    }

    @Test
    public void test_malformed_date_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023/11/30"));
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("not-a-date"));
    }

    @Test
    public void test_parse_duration_basic() {
        final var result = TemporalParser.parseDuration("P1Y2M3DT4H5M6.5S");
        assertEquals(new DurationFields(1, 2, 0, 3, 4, 5, 6, 500, 0, 0), result);
    }

    @Test
    public void test_parse_duration_weeks() {
        final var result = TemporalParser.parseDuration("P3W");
        assertEquals(3, result.weeks());
    }

    @Test
    public void test_parse_duration_negative_leading_sign() {
        final var result = TemporalParser.parseDuration("-P1Y2M3D");
        assertEquals(-1, result.years());
        assertEquals(-2, result.months());
        assertEquals(-3, result.days());
    }

    @Test
    public void test_parse_duration_rejects_unicode_minus() {
        // Every Temporal string sign is ASCII-only; U+2212 MINUS SIGN is rejected everywhere,
        // including the duration's own leading sign, per test262.
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDuration("−P1D"));
    }

    @Test
    public void test_parse_duration_time_only() {
        final var result = TemporalParser.parseDuration("PT1H");
        assertEquals(1, result.hours());
        assertEquals(0, result.days());
    }

    @Test
    public void test_parse_duration_nanosecond_fraction() {
        final var result = TemporalParser.parseDuration("PT0.000000001S");
        assertEquals(0, result.seconds());
        assertEquals(0, result.milliseconds());
        assertEquals(0, result.microseconds());
        assertEquals(1, result.nanoseconds());
    }

    @Test
    public void test_parse_duration_rejects_empty() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDuration("P"));
    }

    @Test
    public void test_parse_duration_rejects_dangling_time_designator() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDuration("P1YT"));
    }

    @Test
    public void test_parse_duration_rejects_missing_p_designator() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDuration("1Y2M3D"));
    }

    @Test
    public void test_parse_duration_rejects_out_of_order_components() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDuration("P1D2Y"));
    }

    @Test
    public void test_parse_time_with_leading_t() {
        final var result = TemporalParser.parseTime("T12:34:56");
        assertEquals(new IsoTimeFields(12, 34, 56, 0, 0, 0), result.time());
    }

    @Test
    public void test_parse_time_with_offset_is_ignored() {
        final var result = TemporalParser.parseTime("12:34:56+01:00");
        assertEquals(new IsoTimeFields(12, 34, 56, 0, 0, 0), result.time());
    }

    // A bare time string with a 'Z' UTC designator is rejected outright (the fallback full
    // date-time parse also cannot make "12:34:56Z" a valid date, so the original error surfaces).
    @Test
    public void test_parse_time_rejects_bare_z() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("12:34:56Z"));
    }

    // parseTime falls back to a full date-time string, discarding the date part, when the bare-time
    // grammar cannot consume the whole input.
    @Test
    public void test_parse_time_falls_back_to_full_date_time_string() {
        final var result = TemporalParser.parseTime("2023-11-30T12:34:56");
        assertEquals(new IsoTimeFields(12, 34, 56, 0, 0, 0), result.time());
        assertNull(result.calendar());
    }

    // The fallback full date-time parse succeeds but is a date-only string (no time part) - the
    // original bare-time error is re-thrown, not a new one about the missing time.
    @Test
    public void test_parse_time_fallback_date_only_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("2023-11-30"));
    }

    // The fallback full date-time parse succeeds with a 'Z' UTC designator - still rejected, since a
    // bare Plain time string never accepts one.
    @Test
    public void test_parse_time_fallback_with_z_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("2023-11-30T12:34:56Z"));
    }

    // Neither the bare-time grammar nor the full date-time fallback can parse garbage input.
    @Test
    public void test_parse_time_fallback_also_fails() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("not-a-time"));
    }

    @Test
    public void test_parse_time_rejects_invalid_minute() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("12:70:00"));
    }

    @Test
    public void test_parse_time_rejects_invalid_second() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("12:00:61"));
    }

    @Test
    public void test_parse_year_month_expanded_sign() {
        final var result = TemporalParser.parseYearMonth("+002023-11");
        assertEquals(new Iso8601Fields(2023, 11, 1), result.date());
    }

    // A leading single dash that is not a doubled "--" prefix is not a valid reduced month-day, so
    // parsing falls back to (and fails on) the full date-time grammar.
    @Test
    public void test_parse_month_day_single_leading_dash_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseMonthDay("-11-30"));
    }

    // A three-component "MM-DD-XX" shape is not a valid reduced month-day (a trailing separator
    // follows the day) and also is not a valid full date, so the whole parse fails.
    @Test
    public void test_parse_month_day_trailing_dash_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseMonthDay("11-30-01"));
    }

    @Test
    public void test_parse_time_zone_identifier_rejects_empty() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTimeZoneIdentifier(""));
    }

    @Test
    public void test_parse_time_zone_identifier_flexible_extracts_bracket_annotation() {
        assertEquals("America/Los_Angeles",
                TemporalParser.parseTimeZoneIdentifierFlexible("2023-11-30T12:34:56-08:00[America/Los_Angeles]"));
    }

    // A bare 'Z' with no bracket annotation names the UTC time zone itself.
    @Test
    public void test_parse_time_zone_identifier_flexible_bare_z_means_utc() {
        assertEquals("UTC", TemporalParser.parseTimeZoneIdentifierFlexible("2023-11-30T12:34:56Z"));
    }

    // Absent a bracket annotation or 'Z', the numeric UTC offset itself is used as the identifier.
    @Test
    public void test_parse_time_zone_identifier_flexible_uses_numeric_offset() {
        assertEquals("+05:00", TemporalParser.parseTimeZoneIdentifierFlexible("2023-11-30T12:34:56+05:00"));
    }

    // A full date-time string with neither an offset nor a bracket annotation carries no time zone
    // information at all, so the original bare-TimeZoneIdentifier error surfaces.
    @Test
    public void test_parse_time_zone_identifier_flexible_rejects_no_time_zone_info() {
        assertThrows(RangeErrorException.class,
                () -> TemporalParser.parseTimeZoneIdentifierFlexible("2023-11-30T12:34:56"));
    }

    @Test
    public void test_parse_time_zone_identifier_flexible_rejects_unparseable_string() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTimeZoneIdentifierFlexible("###"));
    }

    // A duration's fractional hours redistribute into minutes/seconds/sub-second units.
    @Test
    public void test_parse_duration_fractional_hours() {
        final var result = TemporalParser.parseDuration("PT1.5H");
        assertEquals(1, result.hours());
        assertEquals(30, result.minutes());
        assertEquals(0, result.seconds());
    }

    // A duration's fractional minutes redistribute into seconds/sub-second units.
    @Test
    public void test_parse_duration_fractional_minutes() {
        final var result = TemporalParser.parseDuration("PT1.5M");
        assertEquals(1, result.minutes());
        assertEquals(30, result.seconds());
    }

    @Test
    public void test_parse_date_rejects_minus_zero_expanded_year() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("-000000-01-01"));
    }

    @Test
    public void test_parse_instant_rejects_offset_hour_out_of_range() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseInstant("2023-11-30T12:00:00+25:00"));
    }

    @Test
    public void test_parse_instant_rejects_offset_minute_out_of_range() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseInstant("2023-11-30T12:00:00+01:70"));
    }

    @Test
    public void test_parse_instant_rejects_offset_second_out_of_range() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseInstant("2023-11-30T12:00:00+01:00:70"));
    }

    @Test
    public void test_unterminated_annotation_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-11-30[u-ca=iso8601"));
    }

    @Test
    public void test_uppercase_annotation_key_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-11-30[U-CA=iso8601]"));
    }

    @Test
    public void test_empty_annotation_key_rejected() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseDate("2023-11-30[=bar]"));
    }

    // Underscores are permitted in annotation keys per RFC 9557.
    @Test
    public void test_annotation_key_with_underscore_accepted() {
        final var result = TemporalParser.parseDate("2023-11-30[foo_bar=baz]");
        assertEquals(new Iso8601Fields(2023, 11, 30), result.date());
    }

    // Two non-critical calendar annotations are allowed even with disagreeing values - the first
    // value wins.
    @Test
    public void test_duplicate_non_critical_calendar_annotation_keeps_first() {
        final var result = TemporalParser.parseDate("2023-11-30[u-ca=iso8601][u-ca=hebrew]");
        assertEquals("iso8601", result.calendar());
    }

    // A critical calendar annotation followed by any other calendar annotation (critical or not) is
    // rejected, even when the values agree.
    @Test
    public void test_duplicate_calendar_annotation_rejected_when_first_critical() {
        assertThrows(RangeErrorException.class,
                () -> TemporalParser.parseDate("2023-11-30[!u-ca=iso8601][u-ca=iso8601]"));
    }

    @Test
    public void test_duplicate_calendar_annotation_rejected_when_both_critical() {
        assertThrows(RangeErrorException.class,
                () -> TemporalParser.parseDate("2023-11-30[!u-ca=iso8601][!u-ca=iso8601]"));
    }
}
