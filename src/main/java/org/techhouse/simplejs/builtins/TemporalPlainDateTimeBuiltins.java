package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCode;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCodeNumericValue;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.requireMonthCodeSyntax;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeArithmetic.add;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeArithmetic.since;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeArithmetic.subtract;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeArithmetic.until;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.getISOFields;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.round;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.toPlainDate;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.toPlainMonthDay;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.toPlainTime;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.toPlainYearMonth;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.toStringMethod;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeConversions.toZonedDateTime;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeWith.with;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeWith.withCalendar;
import static org.techhouse.simplejs.builtins.temporal.PlainDateTimeWith.withPlainTime;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireCalendarString;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireValidCalendarField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toPositiveIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.regulateTime;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.toNanosOfDay;
import static org.techhouse.simplejs.internal.temporal.TemporalAccessors.installGetter;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalPlainDateTimeBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "withPlainTime", "add", "subtract",
            "until", "since", "round", "equals", "toPlainDate", "toPlainTime", "toPlainYearMonth", "toPlainMonthDay",
            "toZonedDateTime", "toString", "toJSON", "toLocaleString", "getISOFields", "valueOf");

    private static final BigInteger INSTANT_EPOCH_NANOS_LIMIT = BigInteger.valueOf(864)
            .multiply(BigInteger.TEN.pow(19));

    private TemporalPlainDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainDateTime", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.PlainDateTime", thisArg);
            return withNewTargetPrototype(construct(args, ops), ops);
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

    private static JsTemporalPlainDateTime construct(List<JsValue> args, InterpreterOps ops) {
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
            requireCalendarString(calendarArg);
        }
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond,
                RegulateOverflow.REJECT);
        return dateTime(date, time);
    }

    private static int intOrZero(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return 0;
        }
        return toIntegerField(args.get(index), "field", ops);
    }

    private static void requireWithinLimits(Iso8601Fields date, IsoTimeFields time) {
        final var epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        final var epochNanos = BigInteger.valueOf(epochDay).multiply(NANOS_PER_DAY).add(toNanosOfDay(time));
        final var minLimit = INSTANT_EPOCH_NANOS_LIMIT.negate().subtract(NANOS_PER_DAY);
        final var maxLimit = INSTANT_EPOCH_NANOS_LIMIT.add(NANOS_PER_DAY);
        if (epochNanos.compareTo(minLimit) <= 0 || epochNanos.compareTo(maxLimit) >= 0) {
            throw new RangeErrorException(
                    "Temporal.PlainDateTime outside of representable range: " + date + " " + time);
        }
    }

    public static JsTemporalPlainDateTime dateTime(Iso8601Fields date, IsoTimeFields time) {
        requireWithinLimits(date, time);
        return new JsTemporalPlainDateTime(date, time);
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
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0)));
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

    public static JsTemporalPlainDateTime toDateTime(JsValue item, InterpreterOps ops) {
        return toDateTime(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalPlainDateTime toDateTime(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return dateTime(dt.date(), dt.time());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDateTime wrapped) {
            readOverflowOption(optionsArg, ops);
            return dateTime(wrapped.date(), wrapped.time());
        }
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return dateTime(pd.fields(), new IsoTimeFields(0, 0, 0, 0, 0, 0));
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            final var isoFields = zdt.isoFieldsAtLocal();
            return dateTime(isoFields.date(), isoFields.time());
        }
        if (item instanceof JsString s) {
            final var parsed = TemporalParser.parseDate(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.requireBuiltinCalendar(parsed.calendar());
            }
            readOverflowOption(optionsArg, ops);
            final var time = parsed.time() != null ? parsed.time() : new IsoTimeFields(0, 0, 0, 0, 0, 0);
            return dateTime(parsed.date(), time);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return dateTimeFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainDateTime");
    }

    private static JsTemporalPlainDateTime dateTimeFromFields(JsValue obj, JsValue optionsArg, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var dayValue = ops.getMember(obj, new JsString("day"));
        if (dayValue instanceof JsUndefined) {
            throw new TypeErrorException("day is required");
        }
        final var day = toPositiveIntegerField(dayValue, "day", ops);
        final var hour = fieldOrZero(obj, "hour", ops);
        final var microsecond = fieldOrZero(obj, "microsecond", ops);
        final var millisecond = fieldOrZero(obj, "millisecond", ops);
        final var minute = fieldOrZero(obj, "minute", ops);
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final Integer month = monthValue instanceof JsUndefined
                ? null
                : toPositiveIntegerField(monthValue, "month", ops);
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final var monthCode = monthCodeValue instanceof JsUndefined
                ? null
                : requireMonthCodeSyntax(monthCodeValue, ops);
        if (month == null && monthCode == null) {
            throw new TypeErrorException("month or monthCode is required");
        }
        final var nanosecond = fieldOrZero(obj, "nanosecond", ops);
        final var second = fieldOrZero(obj, "second", ops);
        final var yearValue = ops.getMember(obj, new JsString("year"));
        if (yearValue instanceof JsUndefined) {
            throw new TypeErrorException("year is required");
        }
        final var year = toIntegerField(yearValue, "year", ops);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var date = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return dateTime(date, time);
    }

    public static int resolveMonthValue(Integer month, String monthCode) {
        if (monthCode == null) {
            return month;
        }
        final var resolved = monthCodeNumericValue(monthCode);
        if (month != null && month != resolved) {
            throw new RangeErrorException("month and monthCode are inconsistent");
        }
        return resolved;
    }

    public static int resolveMonthValue(Integer month, String monthCode, int defaultMonth) {
        if (month == null && monthCode == null) {
            return defaultMonth;
        }
        return resolveMonthValue(month, monthCode);
    }

    private static int fieldOrZero(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? 0 : toIntegerField(value, name, ops);
    }

    private static JsValue equalsMethod(JsTemporalPlainDateTime receiver, JsValue otherArg, InterpreterOps ops) {
        return JsBoolean.of(receiver.sameValue(toDateTime(otherArg, ops)));
    }

}
