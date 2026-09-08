package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_HOUR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MICRO;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MILLI;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MINUTE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalTimeFields {
    public static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case HOUR -> NANOS_PER_HOUR;
            case MINUTE -> NANOS_PER_MINUTE;
            case SECOND -> NANOS_PER_SECOND;
            case MILLISECOND -> NANOS_PER_MILLI;
            case MICROSECOND -> NANOS_PER_MICRO;
            case NANOSECOND -> BigInteger.ONE;
            default -> throw new RangeErrorException("unit has no fixed nanosecond length: " + unit);
        };
    }

    public static void validateRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new RangeErrorException(name + " must be in the range " + min + ".." + max + ", got " + value);
        }
    }

    public static IsoTimeFields regulateTime(int hour, int minute, int second, int millisecond, int microsecond,
            int nanosecond, RegulateOverflow overflow) {
        if (overflow == RegulateOverflow.CONSTRAIN) {
            return new IsoTimeFields(Math.clamp(hour, 0, 23), Math.clamp(minute, 0, 59), Math.clamp(second, 0, 59),
                    Math.clamp(millisecond, 0, 999), Math.clamp(microsecond, 0, 999), Math.clamp(nanosecond, 0, 999));
        }
        validateRange("hour", hour, 0, 23);
        validateRange("minute", minute, 0, 59);
        validateRange("second", second, 0, 59);
        validateRange("millisecond", millisecond, 0, 999);
        validateRange("microsecond", microsecond, 0, 999);
        validateRange("nanosecond", nanosecond, 0, 999);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    public static BigInteger toNanosOfDay(IsoTimeFields t) {
        return BigInteger.valueOf(t.hour()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf(t.minute()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf(t.second()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf(t.millisecond()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf(t.microsecond()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf(t.nanosecond()));
    }

    public static IsoTimeFields timeFromObjectRequireAny(JsValue obj, InterpreterOps ops) {
        final var hourValue = ops.getMember(obj, new JsString("hour"));
        final var hour = hourValue instanceof JsUndefined ? 0 : toIntegerField(hourValue, "hour", ops);
        final var microsecondValue = ops.getMember(obj, new JsString("microsecond"));
        final var microsecond = microsecondValue instanceof JsUndefined
                ? 0
                : toIntegerField(microsecondValue, "microsecond", ops);
        final var millisecondValue = ops.getMember(obj, new JsString("millisecond"));
        final var millisecond = millisecondValue instanceof JsUndefined
                ? 0
                : toIntegerField(millisecondValue, "millisecond", ops);
        final var minuteValue = ops.getMember(obj, new JsString("minute"));
        final var minute = minuteValue instanceof JsUndefined ? 0 : toIntegerField(minuteValue, "minute", ops);
        final var nanosecondValue = ops.getMember(obj, new JsString("nanosecond"));
        final var nanosecond = nanosecondValue instanceof JsUndefined
                ? 0
                : toIntegerField(nanosecondValue, "nanosecond", ops);
        final var secondValue = ops.getMember(obj, new JsString("second"));
        final var second = secondValue instanceof JsUndefined ? 0 : toIntegerField(secondValue, "second", ops);
        if (hourValue instanceof JsUndefined && microsecondValue instanceof JsUndefined
                && millisecondValue instanceof JsUndefined && minuteValue instanceof JsUndefined
                && nanosecondValue instanceof JsUndefined && secondValue instanceof JsUndefined) {
            throw new TypeErrorException("Invalid time-like object: no recognized properties");
        }
        return regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, RegulateOverflow.CONSTRAIN);
    }

    private TemporalTimeFields() {
    }
}
