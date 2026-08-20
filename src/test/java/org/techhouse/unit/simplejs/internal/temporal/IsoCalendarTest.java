package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;

public class IsoCalendarTest {
    @Test
    public void test_leap_year_rule() {
        assertTrue(IsoCalendar.isLeapYear(2000));
        assertTrue(IsoCalendar.isLeapYear(2024));
        assertFalse(IsoCalendar.isLeapYear(1900));
        assertFalse(IsoCalendar.isLeapYear(2023));
        assertTrue(IsoCalendar.isLeapYear(0));
    }

    @Test
    public void test_days_in_month_february() {
        assertEquals(29, IsoCalendar.daysInMonth(2024, 2));
        assertEquals(28, IsoCalendar.daysInMonth(2023, 2));
        assertEquals(28, IsoCalendar.daysInMonth(1900, 2));
        assertEquals(29, IsoCalendar.daysInMonth(2000, 2));
    }

    @Test
    public void test_days_in_year() {
        assertEquals(366, IsoCalendar.daysInYear(2024));
        assertEquals(365, IsoCalendar.daysInYear(2023));
    }

    @Test
    public void test_regulate_date_reject_feb29_non_leap_year() {
        assertThrows(RangeErrorException.class, () -> IsoCalendar.regulateDate(2023, 2, 29, RegulateOverflow.REJECT));
    }

    @Test
    public void test_regulate_date_constrain_feb29_non_leap_year() {
        final var result = IsoCalendar.regulateDate(2023, 2, 29, RegulateOverflow.CONSTRAIN);
        assertEquals(new Iso8601Fields(2023, 2, 28), result);
    }

    @Test
    public void test_regulate_date_reject_feb29_leap_year_is_valid() {
        final var result = IsoCalendar.regulateDate(2024, 2, 29, RegulateOverflow.REJECT);
        assertEquals(new Iso8601Fields(2024, 2, 29), result);
    }

    @Test
    public void test_regulate_date_reject_day_31_overflow() {
        assertThrows(RangeErrorException.class, () -> IsoCalendar.regulateDate(2023, 4, 31, RegulateOverflow.REJECT));
    }

    @Test
    public void test_regulate_date_constrain_day_31_overflow() {
        final var result = IsoCalendar.regulateDate(2023, 4, 31, RegulateOverflow.CONSTRAIN);
        assertEquals(new Iso8601Fields(2023, 4, 30), result);
    }

    @Test
    public void test_regulate_date_constrain_month_overflow() {
        final var result = IsoCalendar.regulateDate(2023, 13, 15, RegulateOverflow.CONSTRAIN);
        assertEquals(new Iso8601Fields(2023, 12, 15), result);
    }

    @Test
    public void test_regulate_date_constrain_month_underflow() {
        final var result = IsoCalendar.regulateDate(2023, 0, 15, RegulateOverflow.CONSTRAIN);
        assertEquals(new Iso8601Fields(2023, 1, 15), result);
    }

    @Test
    public void test_regulate_date_reject_month_out_of_range() {
        assertThrows(RangeErrorException.class, () -> IsoCalendar.regulateDate(2023, 13, 1, RegulateOverflow.REJECT));
        assertThrows(RangeErrorException.class, () -> IsoCalendar.regulateDate(2023, 0, 1, RegulateOverflow.REJECT));
    }

    @Test
    public void test_balance_iso_date_day_overflow_into_next_month() {
        final var result = IsoCalendar.balanceIsoDate(2023, 1, 45);
        assertEquals(new Iso8601Fields(2023, 2, 14), result);
    }

    @Test
    public void test_balance_iso_date_month_overflow_into_next_year() {
        final var result = IsoCalendar.balanceIsoDate(2023, 13, 1);
        assertEquals(new Iso8601Fields(2024, 1, 1), result);
    }

    @Test
    public void test_balance_iso_date_negative_day_underflow_into_previous_month() {
        final var result = IsoCalendar.balanceIsoDate(2023, 3, -5);
        assertEquals(new Iso8601Fields(2023, 2, 23), result);
    }

    @Test
    public void test_balance_iso_date_negative_month_underflow_into_previous_year() {
        final var result = IsoCalendar.balanceIsoDate(2023, -1, 1);
        assertEquals(new Iso8601Fields(2022, 11, 1), result);
    }

    @Test
    public void test_compare_iso_date() {
        final var a = new Iso8601Fields(2023, 6, 15);
        final var b = new Iso8601Fields(2023, 6, 16);
        final var c = new Iso8601Fields(2023, 6, 15);
        assertTrue(IsoCalendar.compareIsoDate(a, b) < 0);
        assertTrue(IsoCalendar.compareIsoDate(b, a) > 0);
        assertEquals(0, IsoCalendar.compareIsoDate(a, c));
    }

    @Test
    public void test_day_of_week() {
        assertEquals(1, IsoCalendar.dayOfWeek(new Iso8601Fields(2024, 1, 1)));
        assertEquals(7, IsoCalendar.dayOfWeek(new Iso8601Fields(2024, 1, 7)));
    }

    @Test
    public void test_day_of_year() {
        assertEquals(1, IsoCalendar.dayOfYear(new Iso8601Fields(2024, 1, 1)));
        assertEquals(366, IsoCalendar.dayOfYear(new Iso8601Fields(2024, 12, 31)));
        assertEquals(365, IsoCalendar.dayOfYear(new Iso8601Fields(2023, 12, 31)));
    }

    @Test
    public void test_week_of_year_dec_31_belongs_to_week_1_of_next_year() {
        final var date = new Iso8601Fields(2018, 12, 31);
        assertEquals(1, IsoCalendar.weekOfYear(date));
        assertEquals(2019, IsoCalendar.yearOfWeek(date));
    }

    @Test
    public void test_week_of_year_jan_1_belongs_to_week_52_of_previous_year() {
        final var date = new Iso8601Fields(2023, 1, 1);
        assertEquals(52, IsoCalendar.weekOfYear(date));
        assertEquals(2022, IsoCalendar.yearOfWeek(date));
    }

    @Test
    public void test_week_of_year_jan_1_can_belong_to_week_1() {
        final var date = new Iso8601Fields(2024, 1, 1);
        assertEquals(1, IsoCalendar.weekOfYear(date));
        assertEquals(2024, IsoCalendar.yearOfWeek(date));
    }
}
