package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_EPOCH_DAY;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_EPOCH_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_HOUR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MICRO;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MILLI;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MINUTE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.DurationStringParser;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalFields {
    public static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            final var fields = DurationStringParser.parseDuration(s.getValue());
            DurationMath.sign(fields);
            return fields;
        }
        if (InterpreterUtils.isObjectLike(value)) {
            final var days = readDurationField(value, "days", ops);
            final var hours = readDurationField(value, "hours", ops);
            final var microseconds = readDurationField(value, "microseconds", ops);
            final var milliseconds = readDurationField(value, "milliseconds", ops);
            final var minutes = readDurationField(value, "minutes", ops);
            final var months = readDurationField(value, "months", ops);
            final var nanoseconds = readDurationField(value, "nanoseconds", ops);
            final var seconds = readDurationField(value, "seconds", ops);
            final var weeks = readDurationField(value, "weeks", ops);
            final var years = readDurationField(value, "years", ops);
            if (years == null && months == null && weeks == null && days == null && hours == null && minutes == null
                    && seconds == null && milliseconds == null && microseconds == null && nanoseconds == null) {
                throw new TypeErrorException("Duration-like object must contain at least one recognized property");
            }
            final var fields = new DurationFields(orZeroDuration(years), orZeroDuration(months), orZeroDuration(weeks),
                    orZeroDuration(days), orZeroDuration(hours), orZeroDuration(minutes), orZeroDuration(seconds),
                    orZeroDuration(milliseconds), orZeroDuration(microseconds), orZeroDuration(nanoseconds));
            DurationMath.sign(fields);
            return fields;
        }
        throw new TypeErrorException(
                "Expected a Temporal.Duration, an ISO 8601 duration string, or a duration-like object");
    }

    public static void requireValidCalendarField(JsValue obj, InterpreterOps ops) {
        final var calendarValue = ops.getMember(obj, new JsString("calendar"));
        if (calendarValue instanceof JsUndefined || calendarValue instanceof JsTemporalPlainDate
                || calendarValue instanceof JsTemporalPlainDateTime || calendarValue instanceof JsTemporalPlainMonthDay
                || calendarValue instanceof JsTemporalPlainYearMonth
                || calendarValue instanceof JsTemporalZonedDateTime) {
            return;
        }
        if (!(calendarValue instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated(s.getValue());
    }

    public static int toIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        final var truncated = number < 0 ? Math.ceil(number) : Math.floor(number);
        return (int) truncated;
    }

    public static Double readDurationField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            return null;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number)) {
            throw new RangeErrorException(name + " must be an integer");
        }
        return number;
    }

    public static double orZeroDuration(Double value) {
        return value == null ? 0.0 : value;
    }

    public static int toPositiveIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var result = toIntegerField(value, name, ops);
        if (result < 1) {
            throw new RangeErrorException(name + " must be a positive integer, got " + result);
        }
        return result;
    }

    public static void requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.requireBuiltinCalendar(s.getValue());
    }

    public static int requiredYearField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("year"));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException("year is required");
        }
        return toIntegerField(value, "year", ops);
    }

    public static void requireDateInRange(Iso8601Fields date) {
        final long epochDay;
        try {
            epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        } catch (DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
        if (epochDay < MIN_EPOCH_DAY || epochDay > MAX_EPOCH_DAY) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
    }

    public static BigInteger toDurationNanos(DurationFields f) {
        return BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
    }

    private TemporalFields() {
    }
}
