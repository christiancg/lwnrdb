package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalParser.isDigit;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.isSign;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.readFractionDigits;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.requireEnd;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class DurationStringParser {
    public static DurationFields parseDuration(String input) {
        final var cursor = new TemporalCursor(input);
        var signFactor = 1;
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            signFactor = cursor.peek() == '+' ? 1 : -1;
            cursor.advance();
        }
        if (cursor.atEnd() || !isDesignator(cursor.peek(), 'P')) {
            throw new RangeErrorException("Invalid Temporal duration string: " + input);
        }
        cursor.advance();

        final var years = tryReadComponent(cursor, 'Y');
        final var months = tryReadComponent(cursor, 'M');
        final var weeks = tryReadComponent(cursor, 'W');
        final var days = tryReadComponent(cursor, 'D');
        var anyComponent = years != null || months != null || weeks != null || days != null;

        var hours = 0.0;
        var minutes = 0.0;
        var seconds = 0.0;
        var milliseconds = 0.0;
        var microseconds = 0.0;
        var nanoseconds = 0.0;
        if (!cursor.atEnd() && isDesignator(cursor.peek(), 'T')) {
            cursor.advance();
            final var hoursComponent = tryReadTimeComponent(cursor, 'H');
            TimeComponent minutesComponent = null;
            TimeComponent secondsComponent = null;
            if (hoursComponent == null || !hoursComponent.hasFraction()) {
                minutesComponent = tryReadTimeComponent(cursor, 'M');
                if (minutesComponent == null || !minutesComponent.hasFraction()) {
                    secondsComponent = tryReadTimeComponent(cursor, 'S');
                }
            }
            if (hoursComponent == null && minutesComponent == null && secondsComponent == null) {
                throw new RangeErrorException("Duration time designator 'T' requires at least one component: " + input);
            }
            anyComponent = true;
            hours = hoursComponent == null ? 0 : hoursComponent.whole();
            minutes = minutesComponent == null ? 0 : minutesComponent.whole();
            seconds = secondsComponent == null ? 0 : secondsComponent.whole();
            if (hoursComponent != null && hoursComponent.hasFraction()) {
                final var totalNanos = hoursComponent.fractionNanos() * 3600L;
                final var wholeSeconds = totalNanos / 1_000_000_000L;
                final var remainder = totalNanos % 1_000_000_000L;
                final long extraMinutes = wholeSeconds / 60L;
                final long extraSeconds = wholeSeconds % 60L;
                final long ms = remainder / 1_000_000L;
                final long us = (remainder / 1_000L) % 1000L;
                final long ns = remainder % 1000L;
                minutes += extraMinutes;
                seconds += extraSeconds;
                milliseconds = ms;
                microseconds = us;
                nanoseconds = ns;
            } else if (minutesComponent != null && minutesComponent.hasFraction()) {
                final var totalNanos = minutesComponent.fractionNanos() * 60L;
                final long extraSeconds = totalNanos / 1_000_000_000L;
                final var remainder = totalNanos % 1_000_000_000L;
                final long ms = remainder / 1_000_000L;
                final long us = (remainder / 1_000L) % 1000L;
                final long ns = remainder % 1000L;
                seconds += extraSeconds;
                milliseconds = ms;
                microseconds = us;
                nanoseconds = ns;
            } else if (secondsComponent != null && secondsComponent.hasFraction()) {
                final var totalNanos = secondsComponent.fractionNanos();
                final long ms = totalNanos / 1_000_000L;
                final long us = (totalNanos / 1_000L) % 1000L;
                final long ns = totalNanos % 1000L;
                milliseconds = ms;
                microseconds = us;
                nanoseconds = ns;
            }
        }
        requireEnd(cursor);
        if (!anyComponent) {
            throw new RangeErrorException("Empty Temporal duration string: " + input);
        }
        return new DurationFields(normalizeZero(signFactor * orZero(years)), normalizeZero(signFactor * orZero(months)),
                normalizeZero(signFactor * orZero(weeks)), normalizeZero(signFactor * orZero(days)),
                normalizeZero(signFactor * hours), normalizeZero(signFactor * minutes),
                normalizeZero(signFactor * seconds), normalizeZero(signFactor * milliseconds),
                normalizeZero(signFactor * microseconds), normalizeZero(signFactor * nanoseconds));
    }

    static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    static Double tryReadComponent(TemporalCursor cursor, char designator) {
        if (cursor.atEnd() || !isDigit(cursor.peek())) {
            return null;
        }
        final var savedPos = cursor.pos;
        final var digitsStart = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        if (!cursor.atEnd() && isDesignator(cursor.peek(), designator)) {
            final var digits = cursor.source.substring(digitsStart, cursor.pos);
            cursor.advance();
            return Double.parseDouble(digits);
        }
        cursor.pos = savedPos;
        return null;
    }

    record TimeComponent(double whole, boolean hasFraction, long fractionNanos) {
    }

    static TimeComponent tryReadTimeComponent(TemporalCursor cursor, char designator) {
        if (cursor.atEnd() || !isDigit(cursor.peek())) {
            return null;
        }
        final var savedPos = cursor.pos;
        final var digitsStart = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        final var digitsEnd = cursor.pos;
        var hasFraction = false;
        var fraction = "000000000";
        if (!cursor.atEnd() && (cursor.peek() == '.' || cursor.peek() == ',')) {
            cursor.advance();
            fraction = readFractionDigits(cursor);
            hasFraction = true;
        }
        if (!cursor.atEnd() && isDesignator(cursor.peek(), designator)) {
            final var whole = Double.parseDouble(cursor.source.substring(digitsStart, digitsEnd));
            cursor.advance();
            return new TimeComponent(whole, hasFraction, Long.parseLong(fraction));
        }
        cursor.pos = savedPos;
        return null;
    }

    static boolean isDesignator(char c, char designator) {
        return Character.toUpperCase(c) == designator;
    }

    private DurationStringParser() {
    }
}
