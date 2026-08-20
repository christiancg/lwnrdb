package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter.CalendarName;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter.OffsetOption;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter.TimeZoneNameOption;

public class TemporalFormatterTest {
    @Test
    public void test_format_date_basic() {
        assertEquals("2023-11-30", TemporalFormatter.formatDate(new Iso8601Fields(2023, 11, 30)));
    }

    @Test
    public void test_format_date_zero_padded_month_day() {
        assertEquals("2023-01-05", TemporalFormatter.formatDate(new Iso8601Fields(2023, 1, 5)));
    }

    @Test
    public void test_format_date_extended_year_positive() {
        assertEquals("+102023-11-30", TemporalFormatter.formatDate(new Iso8601Fields(102023, 11, 30)));
    }

    @Test
    public void test_format_date_extended_year_negative() {
        assertEquals("-000001-01-01", TemporalFormatter.formatDate(new Iso8601Fields(-1, 1, 1)));
    }

    @Test
    public void test_format_time_no_fraction() {
        assertEquals("12:34:56", TemporalFormatter.formatTime(new IsoTimeFields(12, 34, 56, 0, 0, 0), null));
    }

    @Test
    public void test_format_time_auto_fraction_trims_trailing_zeros() {
        assertEquals("12:34:56.5", TemporalFormatter.formatTime(new IsoTimeFields(12, 34, 56, 500, 0, 0), null));
    }

    @Test
    public void test_format_time_auto_fraction_full_nanoseconds() {
        assertEquals("12:34:56.123456789",
                TemporalFormatter.formatTime(new IsoTimeFields(12, 34, 56, 123, 456, 789), null));
    }

    @Test
    public void test_format_time_explicit_fractional_digits() {
        assertEquals("12:34:56.500", TemporalFormatter.formatTime(new IsoTimeFields(12, 34, 56, 500, 0, 0), 3));
    }

    @Test
    public void test_format_time_zero_fractional_digits_omits_fraction() {
        assertEquals("12:34:56", TemporalFormatter.formatTime(new IsoTimeFields(12, 34, 56, 500, 0, 0), 0));
    }

    @Test
    public void test_format_date_time() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56", TemporalFormatter.formatDateTime(date, time, null, CalendarName.NEVER));
    }

    @Test
    public void test_format_date_time_with_calendar_always() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56[u-ca=iso8601]",
                TemporalFormatter.formatDateTime(date, time, null, CalendarName.ALWAYS));
    }

    @Test
    public void test_format_date_time_with_calendar_critical() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56[!u-ca=iso8601]",
                TemporalFormatter.formatDateTime(date, time, null, CalendarName.CRITICAL));
    }

    @Test
    public void test_format_date_time_auto_omits_iso8601_calendar() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56", TemporalFormatter.formatDateTime(date, time, null, CalendarName.AUTO));
    }

    @Test
    public void test_format_zoned_date_time() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56-08:00[America/Los_Angeles]", TemporalFormatter.formatZonedDateTime(date, time,
                null, "-08:00", "America/Los_Angeles", TimeZoneNameOption.AUTO, OffsetOption.AUTO, CalendarName.NEVER));
    }

    @Test
    public void test_format_zoned_date_time_offset_never() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56[America/Los_Angeles]", TemporalFormatter.formatZonedDateTime(date, time, null,
                "-08:00", "America/Los_Angeles", TimeZoneNameOption.AUTO, OffsetOption.NEVER, CalendarName.NEVER));
    }

    @Test
    public void test_format_zoned_date_time_timezone_never() {
        final var date = new Iso8601Fields(2023, 11, 30);
        final var time = new IsoTimeFields(12, 34, 56, 0, 0, 0);
        assertEquals("2023-11-30T12:34:56-08:00", TemporalFormatter.formatZonedDateTime(date, time, null, "-08:00",
                "America/Los_Angeles", TimeZoneNameOption.NEVER, OffsetOption.AUTO, CalendarName.NEVER));
    }

    @Test
    public void test_format_duration_basic() {
        final var duration = new DurationFields(1, 2, 0, 3, 4, 5, 6, 500, 0, 0);
        assertEquals("P1Y2M3DT4H5M6.5S", TemporalFormatter.formatDuration(duration));
    }

    @Test
    public void test_format_duration_negative() {
        final var duration = new DurationFields(-1, -2, 0, -3, 0, 0, 0, 0, 0, 0);
        assertEquals("-P1Y2M3D", TemporalFormatter.formatDuration(duration));
    }

    @Test
    public void test_format_duration_zero() {
        assertEquals("PT0S", TemporalFormatter.formatDuration(DurationFields.ZERO));
    }

    @Test
    public void test_format_duration_weeks_only() {
        final var duration = new DurationFields(0, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        assertEquals("P3W", TemporalFormatter.formatDuration(duration));
    }

    @Test
    public void test_format_duration_time_only() {
        final var duration = new DurationFields(0, 0, 0, 0, 1, 30, 0, 0, 0, 0);
        assertEquals("PT1H30M", TemporalFormatter.formatDuration(duration));
    }

    @Test
    public void test_format_duration_nanosecond_precision() {
        final var duration = new DurationFields(0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        assertEquals("PT0.000000001S", TemporalFormatter.formatDuration(duration));
    }
}
