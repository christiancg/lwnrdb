package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Function;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.PlainDateTime}: the union of {@code Temporal.PlainDate}'s and {@code
 * Temporal.PlainTime}'s method surface plus {@code toZonedDateTime}. {@link #toPlainYearMonth}/
 * {@link #toPlainMonthDay} return real {@code Temporal.PlainYearMonth}/{@code PlainMonthDay}
 * instances (T4, merged alongside this phase). {@link #toZonedDateTime} returns a real {@code
 * Temporal.ZonedDateTime} (T7), resolving the local date-time to an instant per the
 * {@code disambiguation} option.
 */
public final class TemporalPlainDateTimeBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "withPlainTime", "add", "subtract",
            "until", "since", "round", "equals", "toPlainDate", "toPlainTime", "toPlainYearMonth", "toPlainMonthDay",
            "toZonedDateTime", "toString", "toJSON", "toLocaleString", "getISOFields", "valueOf");

    private static final String[] TIME_FIELD_NAMES = {"hour", "minute", "second", "millisecond", "microsecond",
            "nanosecond"};

    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    private static final long NANOS_PER_HOUR_LONG = 3_600_000_000_000L;
    private static final long NANOS_PER_MINUTE_LONG = 60_000_000_000L;
    private static final long NANOS_PER_SECOND_LONG = 1_000_000_000L;
    private static final long NANOS_PER_MILLI_LONG = 1_000_000L;
    private static final long NANOS_PER_MICRO_LONG = 1_000L;
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private TemporalPlainDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainDateTime", (_, args) -> {
            requireNewTarget();
            return construct(args, ops);
        });
        ctor.setLength(3);
        final var from = new JsNativeFunction("from", (_, args) -> toDateTime(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> new JsNumber(
                JsTemporalPlainDateTime.compare(toDateTime(arg(args, 0), ops), toDateTime(arg(args, 1), ops))));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    // Same narrow requireNewTarget shape as TemporalPlainDateBuiltins: Temporal.PlainDateTime is only
    // ever reached as a member of the Temporal namespace, so a plain call's thisArg is that namespace
    // object rather than undefined - newTarget alone tells a genuine `new`/Reflect.construct call
    // apart (a documented gap: a subclass's super() call is not supported).
    private static void requireNewTarget() {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.PlainDateTime requires 'new'");
        }
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
        final var year = toIntegerField(arg(args, 0), "year", ops);
        final var month = toIntegerField(arg(args, 1), "month", ops);
        final var day = toIntegerField(arg(args, 2), "day", ops);
        final var hour = intOrZero(args, 3, ops);
        final var minute = intOrZero(args, 4, ops);
        final var second = intOrZero(args, 5, ops);
        final var millisecond = intOrZero(args, 6, ops);
        final var microsecond = intOrZero(args, 7, ops);
        final var nanosecond = intOrZero(args, 8, ops);
        final var calendarArg = arg(args, 9);
        if (!(calendarArg instanceof JsUndefined)) {
            requireIso8601(JsCoercion.toStr(calendarArg, ops));
        }
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond,
                RegulateOverflow.REJECT);
        return new JsTemporalPlainDateTime(date, time);
    }

    private static int intOrZero(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return 0;
        }
        return toIntegerField(args.get(index), "field", ops);
    }

    // ToIntegerWithTruncation: a finite number is required, truncated toward zero.
    private static int toIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        final var truncated = number < 0 ? Math.ceil(number) : Math.floor(number);
        return (int) truncated;
    }

    private static IsoTimeFields regulateTime(int hour, int minute, int second, int millisecond, int microsecond,
            int nanosecond, RegulateOverflow overflow) {
        if (overflow == RegulateOverflow.CONSTRAIN) {
            return new IsoTimeFields(Math.clamp(hour, 0, 23), Math.clamp(minute, 0, 59), Math.clamp(second, 0, 59),
                    Math.clamp(millisecond, 0, 999), Math.clamp(microsecond, 0, 999), Math.clamp(nanosecond, 0, 999));
        }
        validateRange("hour", hour, 23);
        validateRange("minute", minute, 59);
        validateRange("second", second, 59);
        validateRange("millisecond", millisecond, 999);
        validateRange("microsecond", microsecond, 999);
        validateRange("nanosecond", nanosecond, 999);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static void validateRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new RangeErrorException(name + " must be in the range 0.." + max + ", got " + value);
        }
    }

    private static void requireIso8601(String calendar) {
        if (!"iso8601".equals(calendar)) {
            throw new RangeErrorException(
                    "Only the \"iso8601\" calendar is supported by this engine, got: " + calendar);
        }
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", r -> new JsNumber(requireReceiver(r, "year").year()));
        installGetter(proto, "month", r -> new JsNumber(requireReceiver(r, "month").month()));
        installGetter(proto, "monthCode", r -> new JsString(monthCode(requireReceiver(r, "monthCode").month())));
        installGetter(proto, "day", r -> new JsNumber(requireReceiver(r, "day").day()));
        installGetter(proto, "hour", r -> new JsNumber(requireReceiver(r, "hour").time().hour()));
        installGetter(proto, "minute", r -> new JsNumber(requireReceiver(r, "minute").time().minute()));
        installGetter(proto, "second", r -> new JsNumber(requireReceiver(r, "second").time().second()));
        installGetter(proto, "millisecond", r -> new JsNumber(requireReceiver(r, "millisecond").time().millisecond()));
        installGetter(proto, "microsecond", r -> new JsNumber(requireReceiver(r, "microsecond").time().microsecond()));
        installGetter(proto, "nanosecond", r -> new JsNumber(requireReceiver(r, "nanosecond").time().nanosecond()));
        installGetter(proto, "dayOfWeek",
                r -> new JsNumber(IsoCalendar.dayOfWeek(requireReceiver(r, "dayOfWeek").date())));
        installGetter(proto, "dayOfYear",
                r -> new JsNumber(IsoCalendar.dayOfYear(requireReceiver(r, "dayOfYear").date())));
        installGetter(proto, "weekOfYear",
                r -> new JsNumber(IsoCalendar.weekOfYear(requireReceiver(r, "weekOfYear").date())));
        installGetter(proto, "yearOfWeek",
                r -> new JsNumber(IsoCalendar.yearOfWeek(requireReceiver(r, "yearOfWeek").date())));
        installGetter(proto, "daysInWeek", r -> {
            requireReceiver(r, "daysInWeek");
            return new JsNumber(7);
        });
        installGetter(proto, "daysInMonth", r -> {
            final var d = requireReceiver(r, "daysInMonth");
            return new JsNumber(IsoCalendar.daysInMonth(d.year(), d.month()));
        });
        installGetter(proto, "daysInYear", r -> {
            final var d = requireReceiver(r, "daysInYear");
            return new JsNumber(IsoCalendar.daysInYear(d.year()));
        });
        installGetter(proto, "monthsInYear", r -> {
            requireReceiver(r, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", r -> {
            final var d = requireReceiver(r, "inLeapYear");
            return JsBoolean.of(IsoCalendar.isLeapYear(d.year()));
        });
        installGetter(proto, "calendarId", r -> {
            requireReceiver(r, "calendarId");
            return new JsString("iso8601");
        });
        installGetter(proto, "era", r -> {
            requireReceiver(r, "era");
            return JsUndefined.getInstance();
        });
        installGetter(proto, "eraYear", r -> {
            requireReceiver(r, "eraYear");
            return JsUndefined.getInstance();
        });
    }

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }

    private static JsTemporalPlainDateTime requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainDateTime dt) {
            return dt;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDateTime wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.PlainDateTime.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue getMethod(JsTemporalPlainDateTime receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0), ops));
            case "withPlainTime" ->
                new JsNativeFunction("withPlainTime", (_, args) -> withPlainTime(receiver, arg(args, 0), ops));
            case "add" -> new JsNativeFunction("add", (_, args) -> add(receiver, arg(args, 0), arg(args, 1), ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> subtract(receiver, arg(args, 0), arg(args, 1), ops));
            case "until" ->
                new JsNativeFunction("until", (_, args) -> until(receiver, arg(args, 0), arg(args, 1), ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> since(receiver, arg(args, 0), arg(args, 1), ops));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toPlainDate" -> new JsNativeFunction("toPlainDate", (_, _) -> toPlainDate(receiver));
            case "toPlainTime" -> new JsNativeFunction("toPlainTime", (_, _) -> toPlainTime(receiver));
            case "toPlainYearMonth" -> new JsNativeFunction("toPlainYearMonth", (_, _) -> toPlainYearMonth(receiver));
            case "toPlainMonthDay" -> new JsNativeFunction("toPlainMonthDay", (_, _) -> toPlainMonthDay(receiver));
            case "toZonedDateTime" -> new JsNativeFunction("toZonedDateTime",
                    (_, args) -> toZonedDateTime(receiver, arg(args, 0), arg(args, 1), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.PlainDateTime to a primitive value with valueOf; use compare() or "
                                + "equals() instead");
            });
            default -> null;
        };
    }

    // ToTemporalDateTime: accepts a real PlainDateTime (copied), an ISO date-time string, or a
    // date-time-like object (date fields required, time fields default to 0, regulated per overflow).
    private static JsTemporalPlainDateTime toDateTime(JsValue item, InterpreterOps ops) {
        return toDateTime(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalPlainDateTime toDateTime(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        final var overflow = readOverflowOption(optionsArg, ops);
        if (item instanceof JsTemporalPlainDateTime dt) {
            return new JsTemporalPlainDateTime(dt.date(), dt.time());
        }
        if (item instanceof JsString s) {
            final var parsed = TemporalParser.parseDateTime(s.getValue());
            return new JsTemporalPlainDateTime(parsed.date(), parsed.time());
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return dateTimeFromFields(item, overflow, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainDateTime");
    }

    private static JsTemporalPlainDateTime dateTimeFromFields(JsValue obj, RegulateOverflow overflow,
            InterpreterOps ops) {
        final var year = requiredIntegerField(obj, "year", ops);
        final var month = resolveMonth(obj, ops);
        final var day = requiredIntegerField(obj, "day", ops);
        final var hour = fieldOrDefault(obj, "hour", 0, ops);
        final var minute = fieldOrDefault(obj, "minute", 0, ops);
        final var second = fieldOrDefault(obj, "second", 0, ops);
        final var millisecond = fieldOrDefault(obj, "millisecond", 0, ops);
        final var microsecond = fieldOrDefault(obj, "microsecond", 0, ops);
        final var nanosecond = fieldOrDefault(obj, "nanosecond", 0, ops);
        final var date = IsoCalendar.regulateDate(year, month, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return new JsTemporalPlainDateTime(date, time);
    }

    private static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    private static int resolveMonth(JsValue obj, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final var monthValue = ops.getMember(obj, new JsString("month"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            final var resolved = parseMonthCode(JsCoercion.toStr(monthCodeValue, ops));
            if (!(monthValue instanceof JsUndefined) && toIntegerField(monthValue, "month", ops) != resolved) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return resolved;
        }
        if (monthValue instanceof JsUndefined) {
            throw new TypeErrorException("month or monthCode is required");
        }
        return toIntegerField(monthValue, "month", ops);
    }

    private static int parseMonthCode(String code) {
        if (code.length() == 3 && code.charAt(0) == 'M') {
            try {
                final var value = Integer.parseInt(code.substring(1));
                if (value >= 1 && value <= 12) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Falls through to the RangeError below.
            }
        }
        throw new RangeErrorException("Invalid monthCode: " + code);
    }

    private static int fieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : toIntegerField(value, name, ops);
    }

    private static String monthCode(int month) {
        return "M" + pad2(month);
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static JsValue optionOrUndefined(JsValue optionsArg, String key, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            return JsUndefined.getInstance();
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        return ops.getMember(optionsArg, new JsString(key));
    }

    private static RegulateOverflow readOverflowOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "overflow", ops);
        return value instanceof JsUndefined
                ? RegulateOverflow.CONSTRAIN
                : RegulateOverflow.parse(JsCoercion.toStr(value, ops));
    }

    private static Unit readLargestUnitOption(JsValue optionsArg, Unit fallback, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "largestUnit", ops);
        if (value instanceof JsUndefined) {
            return fallback;
        }
        final var raw = JsCoercion.toStr(value, ops);
        return "auto".equals(raw) ? fallback : Unit.parseTemporalUnit(raw);
    }

    private static Unit readSmallestUnitOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "smallestUnit", ops);
        return value instanceof JsUndefined ? Unit.NANOSECOND : Unit.parseTemporalUnit(JsCoercion.toStr(value, ops));
    }

    private static TemporalFormatter.CalendarName readCalendarNameOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "calendarName", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.CalendarName.AUTO
                : TemporalFormatter.CalendarName.parse(JsCoercion.toStr(value, ops));
    }

    private static long readIncrementOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "roundingIncrement", ops);
        if (value instanceof JsUndefined) {
            return 1;
        }
        final var number = toIntegerField(value, "roundingIncrement", ops);
        if (number < 1 || number > 1_000_000_000) {
            throw new RangeErrorException("roundingIncrement out of range: " + number);
        }
        return number;
    }

    private static RoundingMode readRoundingModeOption(JsValue optionsArg, InterpreterOps ops, RoundingMode fallback) {
        final var value = optionOrUndefined(optionsArg, "roundingMode", ops);
        return value instanceof JsUndefined ? fallback : RoundingMode.parse(JsCoercion.toStr(value, ops));
    }

    // round()/until()/since() reject an out-of-range increment against the unit's natural cycle
    // length (e.g. 24 for hour); "day" only ever accepts an increment of 1, since a PlainDateTime's
    // round() rounds a single day as a whole rather than a multi-day cycle.
    private static void validateRoundingIncrement(long increment, Unit unit) {
        if (unit == Unit.DAY) {
            if (increment != 1) {
                throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit day");
            }
            return;
        }
        final var maximum = switch (unit) {
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            case MILLISECOND, MICROSECOND, NANOSECOND -> 1000;
            default -> throw new RangeErrorException("Invalid unit for rounding: " + unit);
        };
        if (increment < 1 || maximum % increment != 0 || increment == maximum) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    private static RoundingMode negateRoundingMode(RoundingMode mode) {
        return switch (mode) {
            case CEIL -> RoundingMode.FLOOR;
            case FLOOR -> RoundingMode.CEIL;
            case HALF_CEIL -> RoundingMode.HALF_FLOOR;
            case HALF_FLOOR -> RoundingMode.HALF_CEIL;
            default -> mode;
        };
    }

    private static JsObject smallestUnitOptions(JsString value) {
        final var options = new JsObject();
        options.set("smallestUnit", value);
        return options;
    }

    private static JsValue with(JsTemporalPlainDateTime receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || fieldsLike instanceof JsTemporalPlainDateTime) {
            throw new TypeErrorException("Temporal.PlainDateTime.prototype.with argument must be a plain object");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var year = fieldOrDefault(fieldsLike, "year", receiver.year(), ops);
        final var month = resolveMonthWith(fieldsLike, receiver.month(), ops);
        final var day = fieldOrDefault(fieldsLike, "day", receiver.day(), ops);
        final var t = receiver.time();
        final var hour = fieldOrDefault(fieldsLike, "hour", t.hour(), ops);
        final var minute = fieldOrDefault(fieldsLike, "minute", t.minute(), ops);
        final var second = fieldOrDefault(fieldsLike, "second", t.second(), ops);
        final var millisecond = fieldOrDefault(fieldsLike, "millisecond", t.millisecond(), ops);
        final var microsecond = fieldOrDefault(fieldsLike, "microsecond", t.microsecond(), ops);
        final var nanosecond = fieldOrDefault(fieldsLike, "nanosecond", t.nanosecond(), ops);
        final var date = IsoCalendar.regulateDate(year, month, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return new JsTemporalPlainDateTime(date, time);
    }

    private static int resolveMonthWith(JsValue obj, int defaultMonth, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            return parseMonthCode(JsCoercion.toStr(monthCodeValue, ops));
        }
        return fieldOrDefault(obj, "month", defaultMonth, ops);
    }

    // withCalendar is an identity operation in ISO-only mode - the only effect is validating the arg.
    private static JsValue withCalendar(JsTemporalPlainDateTime receiver, JsValue calendarArg, InterpreterOps ops) {
        requireIso8601(JsCoercion.toStr(calendarArg, ops));
        return new JsTemporalPlainDateTime(receiver.date(), receiver.time());
    }

    private static JsValue withPlainTime(JsTemporalPlainDateTime receiver, JsValue timeLike, InterpreterOps ops) {
        return new JsTemporalPlainDateTime(receiver.date(), toPlainTimeOrMidnight(timeLike, ops));
    }

    // ToTemporalTime, narrowed to withPlainTime's needs: an absent argument defaults to midnight
    // (replacing, not merging, unlike with()'s per-field defaults).
    private static IsoTimeFields toPlainTimeOrMidnight(JsValue timeLike, InterpreterOps ops) {
        switch (timeLike) {
            case null -> {
                return new IsoTimeFields(0, 0, 0, 0, 0, 0);
            }
            case JsUndefined _ -> {
                return new IsoTimeFields(0, 0, 0, 0, 0, 0);
            }
            case JsTemporalPlainTime pt -> {
                return pt.getFields();
            }
            case JsString s -> {
                return TemporalParser.parseTime(s.getValue()).time();
            }
            default -> {
            }
        }
        if (InterpreterUtils.isObjectLike(timeLike)) {
            return timeFromObjectRequireAny(timeLike, ops);
        }
        throw new TypeErrorException("Temporal.PlainDateTime.prototype.withPlainTime requires a time-like value");
    }

    private static IsoTimeFields timeFromObjectRequireAny(JsValue obj, InterpreterOps ops) {
        final var values = new int[6];
        var any = false;
        for (var i = 0; i < TIME_FIELD_NAMES.length; i++) {
            final var value = ops.getMember(obj, new JsString(TIME_FIELD_NAMES[i]));
            if (!(value instanceof JsUndefined)) {
                any = true;
                values[i] = toIntegerField(value, TIME_FIELD_NAMES[i], ops);
            }
        }
        if (!any) {
            throw new TypeErrorException("Invalid time-like object: no recognized properties");
        }
        return regulateTime(values[0], values[1], values[2], values[3], values[4], values[5],
                RegulateOverflow.CONSTRAIN);
    }

    // ToTemporalDuration: a real Temporal.Duration (via InterpreterUtils.isObjectLike + getMember
    // duck-typing, so a genuine instance and a plain duration-like object are both accepted), an ISO
    // duration string, or a plain duration-like object.
    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            final var fields = TemporalParser.parseDuration(s.getValue());
            DurationMath.sign(fields);
            return fields;
        }
        if (InterpreterUtils.isObjectLike(value)) {
            final var fields = new DurationFields(durationFieldOrZero(value, "years", ops),
                    durationFieldOrZero(value, "months", ops), durationFieldOrZero(value, "weeks", ops),
                    durationFieldOrZero(value, "days", ops), durationFieldOrZero(value, "hours", ops),
                    durationFieldOrZero(value, "minutes", ops), durationFieldOrZero(value, "seconds", ops),
                    durationFieldOrZero(value, "milliseconds", ops), durationFieldOrZero(value, "microseconds", ops),
                    durationFieldOrZero(value, "nanoseconds", ops));
            DurationMath.sign(fields);
            return fields;
        }
        throw new TypeErrorException(
                "Expected a Temporal.Duration, an ISO 8601 duration string, or a duration-like object");
    }

    // ToIntegerIfIntegral: a Duration-like field must already be an integer (no truncation).
    private static double durationFieldOrZero(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            return 0.0;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number)) {
            throw new RangeErrorException(name + " must be an integer");
        }
        return number;
    }

    private static DurationFields negate(DurationFields d) {
        return DurationMath.negate(d);
    }

    private static JsValue add(JsTemporalPlainDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addDateTime(receiver, toDurationFields(durationLike, ops), readOverflowOption(optionsArg, ops));
    }

    private static JsValue subtract(JsTemporalPlainDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addDateTime(receiver, negate(toDurationFields(durationLike, ops)), readOverflowOption(optionsArg, ops));
    }

    // AddDateTime: the duration's time units are balanced against the receiver's time-of-day first
    // (any overflow/underflow carries a whole day), then the date arithmetic (years/months/weeks plus
    // the day carry) is applied via IsoCalendar.addDate - the same two-phase order addDate itself uses
    // for its own years/months-then-days split, so e.g. "Jan 31, 23:00 + 1 month, 2 hours" regulates
    // against February's real length before the day carry is added.
    private static JsValue addDateTime(JsTemporalPlainDateTime receiver, DurationFields duration,
            RegulateOverflow overflow) {
        final var nanosOfDay = toNanosOfDay(receiver.time());
        final var deltaTimeNanos = toDurationNanos(duration);
        final var total = nanosOfDay.add(deltaTimeNanos);
        final var dayCarry = total.subtract(total.mod(NANOS_PER_DAY)).divide(NANOS_PER_DAY);
        final var newNanosOfDay = total.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = IsoCalendar.addDate(receiver.date(), duration.years(), duration.months(), duration.weeks(),
                duration.days() + dayCarry.doubleValue(), overflow);
        return new JsTemporalPlainDateTime(newDate, fromNanosOfDay(newNanosOfDay.longValueExact()));
    }

    private static BigInteger toNanosOfDay(IsoTimeFields t) {
        return BigInteger.valueOf(t.hour()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf(t.minute()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf(t.second()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf(t.millisecond()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf(t.microsecond()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf(t.nanosecond()));
    }

    private static IsoTimeFields fromNanosOfDay(long nanos) {
        var remaining = nanos;
        final var hour = (int) (remaining / NANOS_PER_HOUR_LONG);
        remaining %= NANOS_PER_HOUR_LONG;
        final var minute = (int) (remaining / NANOS_PER_MINUTE_LONG);
        remaining %= NANOS_PER_MINUTE_LONG;
        final var second = (int) (remaining / NANOS_PER_SECOND_LONG);
        remaining %= NANOS_PER_SECOND_LONG;
        final var millisecond = (int) (remaining / NANOS_PER_MILLI_LONG);
        remaining %= NANOS_PER_MILLI_LONG;
        final var microsecond = (int) (remaining / NANOS_PER_MICRO_LONG);
        final var nanosecond = (int) (remaining % NANOS_PER_MICRO_LONG);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static BigInteger toDurationNanos(DurationFields f) {
        return BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
    }

    private static JsValue until(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    // DifferenceTemporalPlainDateTime: computed once in the "until" direction (receiver -> other),
    // mirroring TemporalPlainTimeBuiltins' since/until pairing - `since` negates the rounding mode
    // before rounding (asymmetric modes like ceil/floor need to flip direction) and negates the
    // resulting fields afterward, rather than recomputing with swapped operands.
    private static JsValue difference(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toDateTime(otherArg, ops);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        // largestUnit defaults to (and "auto" resolves to) whichever of smallestUnit/day is coarser,
        // so a smallestUnit larger than the usual "day" default (e.g. "years") doesn't spuriously
        // conflict with it.
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.DAY) ? smallestUnit : Unit.DAY;
        final var largestUnit = readLargestUnitOption(optionsArg, largestUnitDefault, ops);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        var fields = DurationMath.differenceCalendar(receiver.date(), receiver.time(), other.date(), other.time(),
                largestUnit);
        if (smallestUnit != Unit.NANOSECOND || increment != 1) {
            if (largestUnit.isLargerThan(Unit.DAY)) {
                final var anchor = RelativeDurationMath.Anchor.plain(receiver.date(), receiver.time());
                fields = RelativeDurationMath.roundedDifference(anchor, other.date(), other.time(), largestUnit,
                        smallestUnit, increment, mode);
            } else {
                validateRoundingIncrement(increment, smallestUnit);
                fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
            }
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    private static JsValue equalsMethod(JsTemporalPlainDateTime receiver, JsValue otherArg, InterpreterOps ops) {
        return JsBoolean.of(receiver.sameValue(toDateTime(otherArg, ops)));
    }

    private static JsValue round(JsTemporalPlainDateTime receiver, JsValue roundToArg, InterpreterOps ops) {
        if (roundToArg == null || roundToArg instanceof JsUndefined) {
            throw new TypeErrorException("round() requires an options parameter");
        }
        final var options = roundToArg instanceof JsString unit ? smallestUnitOptions(unit) : roundToArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a string");
        }
        final var smallestUnitValue = optionOrUndefined(options, "smallestUnit", ops);
        if (smallestUnitValue instanceof JsUndefined) {
            throw new RangeErrorException("smallestUnit is required");
        }
        final var smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        if (smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.PlainDateTime.prototype.round: " + smallestUnit.singular());
        }
        final var increment = readIncrementOption(options, ops);
        validateRoundingIncrement(increment, smallestUnit);
        final var mode = readRoundingModeOption(options, ops, RoundingMode.HALF_EXPAND);
        return roundToUnit(receiver, smallestUnit, increment, mode);
    }

    // Rounds the nanosecond-of-day total and carries any overflow/underflow into the date - unlike
    // TemporalPlainTimeBuiltins' equivalent (which wraps modulo a single day, having no date to carry
    // into).
    private static JsTemporalPlainDateTime roundByIncrementNanos(JsTemporalPlainDateTime receiver,
            BigInteger incrementNanos, RoundingMode mode) {
        final var nanosOfDay = toNanosOfDay(receiver.time());
        final var rounded = roundNonNegative(nanosOfDay, incrementNanos, mode);
        final var dayCarry = rounded.divide(NANOS_PER_DAY);
        final var remainder = rounded.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = dayCarry.signum() == 0
                ? receiver.date()
                : IsoCalendar.addDate(receiver.date(), 0, 0, 0, dayCarry.doubleValue(), RegulateOverflow.CONSTRAIN);
        return new JsTemporalPlainDateTime(newDate, fromNanosOfDay(remainder.longValueExact()));
    }

    private static JsTemporalPlainDateTime roundToUnit(JsTemporalPlainDateTime receiver, Unit unit, long increment,
            RoundingMode mode) {
        final var perUnit = unit == Unit.DAY ? NANOS_PER_DAY : nanosPerUnit(unit);
        return roundByIncrementNanos(receiver, perUnit.multiply(BigInteger.valueOf(increment)), mode);
    }

    private static JsTemporalPlainDateTime roundToFractionalDigits(JsTemporalPlainDateTime receiver, int digits,
            RoundingMode mode) {
        return roundByIncrementNanos(receiver, BigInteger.TEN.pow(9 - digits), mode);
    }

    private static BigInteger nanosPerUnit(Unit unit) {
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

    // Non-negative-only rounding (a time-of-day nanosecond count is always in [0, 86400e9)) - same
    // eight-branch rule set as TemporalPlainTimeBuiltins' equivalent, deliberately not shared since
    // that one is private and this one intentionally does not mod the result (the caller needs the
    // raw carry to know whether the date advanced).
    private static BigInteger roundNonNegative(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var dm = value.divideAndRemainder(increment);
        final var quotient = dm[0];
        final var remainder = dm[1];
        if (remainder.signum() == 0) {
            return value;
        }
        final var doubled = remainder.multiply(TWO);
        final var roundedQuotient = switch (mode) {
            case TRUNC, FLOOR -> quotient;
            case CEIL, EXPAND -> quotient.add(BigInteger.ONE);
            case HALF_EXPAND, HALF_CEIL -> doubled.compareTo(increment) >= 0 ? quotient.add(BigInteger.ONE) : quotient;
            case HALF_TRUNC, HALF_FLOOR -> doubled.compareTo(increment) > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case HALF_EVEN -> {
                final var cmp = doubled.compareTo(increment);
                if (cmp > 0) {
                    yield quotient.add(BigInteger.ONE);
                }
                yield cmp < 0 ? quotient : (quotient.testBit(0) ? quotient.add(BigInteger.ONE) : quotient);
            }
        };
        return roundedQuotient.multiply(increment);
    }

    private static JsValue toPlainDate(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainDate(receiver.date());
    }

    private static JsValue toPlainTime(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainTime(receiver.time());
    }

    private static JsValue toPlainYearMonth(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainYearMonth(new Iso8601Fields(receiver.year(), receiver.month(), 1));
    }

    private static JsValue toPlainMonthDay(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainMonthDay(new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR,
                receiver.month(), receiver.day()));
    }

    // Temporal.ZonedDateTime (T7): resolves the receiver's local wall-clock fields to an instant per
    // the `disambiguation` option (default "compatible"), consulting the target zone's transition
    // directly - the same gap/fold resolution TemporalZonedDateTimeBuiltins itself uses - rather than
    // leaning on java.time.ZonedDateTime.of's fixed default behavior.
    private static JsValue toZonedDateTime(JsTemporalPlainDateTime receiver, JsValue timeZoneArg, JsValue optionsArg,
            InterpreterOps ops) {
        final var id = extractTimeZoneId(timeZoneArg, ops);
        final var zone = zoneOf(id);
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var date = receiver.date();
        final var time = receiver.time();
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final ZonedDateTime zdt;
        try {
            final var local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(),
                    time.second(), nanoOfSecond);
            zdt = resolveLocalToZoned(local, zone, disambiguation);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.PlainDateTime for toZonedDateTime: " + e.getMessage());
        }
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(zdt, id);
    }

    private enum Disambiguation {
        COMPATIBLE, EARLIER, LATER, REJECT;

        static Disambiguation parse(String value) {
            return switch (value) {
                case "compatible" -> COMPATIBLE;
                case "earlier" -> EARLIER;
                case "later" -> LATER;
                case "reject" -> REJECT;
                default -> throw new RangeErrorException("Invalid disambiguation option: " + value);
            };
        }
    }

    private static Disambiguation readDisambiguationOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "disambiguation", ops);
        return value instanceof JsUndefined
                ? Disambiguation.COMPATIBLE
                : Disambiguation.parse(JsCoercion.toStr(value, ops));
    }

    private static ZonedDateTime resolveLocalToZoned(LocalDateTime local, ZoneId zone, Disambiguation disambiguation) {
        final var transition = zone.getRules().getTransition(local);
        if (transition == null) {
            return ZonedDateTime.of(local, zone);
        }
        if (transition.isGap()) {
            // See TemporalZonedDateTimeBuiltins.resolveLocal for why the offsets are swapped relative
            // to "earlier"/"later": applying the pre-transition offset to a nonexistent local time
            // lands past the real transition and renders forward-shifted ("later"/"compatible").
            return switch (disambiguation) {
                case REJECT -> throw new RangeErrorException(
                        "Temporal.PlainDateTime.prototype.toZonedDateTime: local time falls in a time zone "
                                + "transition gap and disambiguation is 'reject'");
                case EARLIER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
                case LATER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
            };
        }
        return switch (disambiguation) {
            case REJECT -> throw new RangeErrorException(
                    "Temporal.PlainDateTime.prototype.toZonedDateTime: local time is ambiguous (time zone "
                            + "transition fold) and disambiguation is 'reject'");
            case LATER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
            case EARLIER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
        };
    }

    private static String extractTimeZoneId(JsValue options, InterpreterOps ops) {
        if (options instanceof JsString s) {
            return TemporalParser.parseTimeZoneIdentifier(s.getValue());
        }
        if (options instanceof JsObject obj) {
            final var timeZone = ops.getMember(obj, new JsString("timeZone"));
            if (timeZone instanceof JsString s) {
                return TemporalParser.parseTimeZoneIdentifier(s.getValue());
            }
        }
        throw new TypeErrorException("Temporal.PlainDateTime.prototype.toZonedDateTime requires a timeZone");
    }

    private static ZoneId zoneOf(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid time zone identifier: " + id);
        }
    }

    private static JsValue toStringMethod(JsTemporalPlainDateTime receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        final var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnitValue = optionOrUndefined(optionsArg, "smallestUnit", ops);
        if (!(smallestUnitValue instanceof JsUndefined)) {
            final var unitStr = JsCoercion.toStr(smallestUnitValue, ops);
            if ("minute".equals(unitStr)) {
                final var rounded = roundToUnit(receiver, Unit.MINUTE, 1, mode);
                return new JsString(TemporalFormatter.formatDate(rounded.date()) + "T" + pad2(rounded.time().hour())
                        + ":" + pad2(rounded.time().minute())
                        + TemporalFormatter.formatCalendarAnnotation(calendarName));
            }
            final var unit = Unit.parseTemporalUnit(unitStr);
            requireSecondOrSmallerUnit(unit);
            final var rounded = roundToUnit(receiver, unit, 1, mode);
            return new JsString(TemporalFormatter.formatDateTime(rounded.date(), rounded.time(), digitsForUnit(unit),
                    calendarName));
        }
        final var fsdValue = optionOrUndefined(optionsArg, "fractionalSecondDigits", ops);
        if (!(fsdValue instanceof JsUndefined)) {
            if (fsdValue instanceof JsString s && "auto".equals(s.getValue())) {
                return new JsString(
                        TemporalFormatter.formatDateTime(receiver.date(), receiver.time(), null, calendarName));
            }
            final var digits = toIntegerField(fsdValue, "fractionalSecondDigits", ops);
            if (digits < 0 || digits > 9) {
                throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\"");
            }
            final var rounded = roundToFractionalDigits(receiver, digits, mode);
            return new JsString(TemporalFormatter.formatDateTime(rounded.date(), rounded.time(), digits, calendarName));
        }
        return new JsString(TemporalFormatter.formatDateTime(receiver.date(), receiver.time(), null, calendarName));
    }

    private static void requireSecondOrSmallerUnit(Unit unit) {
        if (unit.isLargerThan(Unit.SECOND)) {
            throw new RangeErrorException(
                    "smallestUnit must be minute, second, millisecond, microsecond, or nanosecond");
        }
    }

    private static int digitsForUnit(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            case NANOSECOND -> 9;
            default -> throw new RangeErrorException(
                    "smallestUnit must be minute, second, millisecond, microsecond, or nanosecond");
        };
    }

    private static JsValue getISOFields(JsTemporalPlainDateTime receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.year()));
        final var t = receiver.time();
        obj.set("isoHour", new JsNumber(t.hour()));
        obj.set("isoMinute", new JsNumber(t.minute()));
        obj.set("isoSecond", new JsNumber(t.second()));
        obj.set("isoMillisecond", new JsNumber(t.millisecond()));
        obj.set("isoMicrosecond", new JsNumber(t.microsecond()));
        obj.set("isoNanosecond", new JsNumber(t.nanosecond()));
        return obj;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
