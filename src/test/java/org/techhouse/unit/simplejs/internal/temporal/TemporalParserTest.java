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
    public void test_parse_time_rejects_leap_second() {
        assertThrows(RangeErrorException.class, () -> TemporalParser.parseTime("23:59:60"));
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
    public void test_parse_duration_negative_via_unicode_minus() {
        final var result = TemporalParser.parseDuration("−P1D");
        assertEquals(-1, result.days());
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
}
