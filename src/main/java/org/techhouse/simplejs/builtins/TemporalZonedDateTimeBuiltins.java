package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCode;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireCalendarString;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeArithmetic.add;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeArithmetic.compare;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeArithmetic.equalsMethod;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeArithmetic.since;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeArithmetic.subtract;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeArithmetic.until;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.getISOFields;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.toPlainDate;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.toPlainDateTime;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.toPlainMonthDay;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.toPlainTime;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.toPlainYearMonth;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeConversions.toStringMethod;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.toZonedDateTime;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeRounding.round;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeWith.with;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeWith.withCalendar;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeWith.withPlainDate;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeWith.withPlainTime;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeWith.withTimeZone;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.epochNanosOf;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.getTimeZoneTransition;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.zoneOf;
import static org.techhouse.simplejs.internal.temporal.TemporalAccessors.installGetter;

import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalZonedDateTimeBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "withTimeZone", "withPlainDate",
            "withPlainTime", "add", "subtract", "until", "since", "round", "startOfDay", "equals", "toInstant",
            "toPlainDate", "toPlainTime", "toPlainDateTime", "toPlainYearMonth", "toPlainMonthDay", "toString",
            "toJSON", "toLocaleString", "getISOFields", "getTimeZoneTransition", "valueOf");

    public enum OffsetOption {
        USE, PREFER, IGNORE, REJECT;

        public static OffsetOption parse(String value) {
            return switch (value) {
                case "use" -> USE;
                case "prefer" -> PREFER;
                case "ignore" -> IGNORE;
                case "reject" -> REJECT;
                default -> throw new RangeErrorException("Invalid offset option: " + value);
            };
        }
    }

    private TemporalZonedDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("ZonedDateTime", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.ZonedDateTime", thisArg);
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(2);
        final var from = new JsNativeFunction("from", (_, args) -> toZonedDateTime(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_,
                args) -> new JsNumber(compare(toZonedDateTime(arg(args, 0), ops), toZonedDateTime(arg(args, 1), ops))));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    private static JsTemporalZonedDateTime construct(List<JsValue> args, InterpreterOps ops) {
        final var epochArg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var epochNanoseconds = NumberBuiltins.toBigIntValue(epochArg, ops).getValue();
        final var timeZoneArg = arg(args, 1);
        if (timeZoneArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.ZonedDateTime requires a timeZone argument");
        }
        if (!(timeZoneArg instanceof JsString timeZoneStr)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var timeZoneId = TimeZoneStringParser.parseTimeZoneIdentifier(timeZoneStr.getValue());
        final var calendarArg = arg(args, 2);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        return JsTemporalZonedDateTime.fromEpochNanoseconds(epochNanoseconds, zoneOf(timeZoneId), timeZoneId);
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", r -> new JsNumber(requireReceiver(r, "year").isoFieldsAtLocal().date().year()));
        installGetter(proto, "month", r -> new JsNumber(requireReceiver(r, "month").isoFieldsAtLocal().date().month()));
        installGetter(proto, "monthCode",
                r -> new JsString(monthCode(requireReceiver(r, "monthCode").isoFieldsAtLocal().date().month())));
        installGetter(proto, "day", r -> new JsNumber(requireReceiver(r, "day").isoFieldsAtLocal().date().day()));
        installGetter(proto, "hour", r -> new JsNumber(requireReceiver(r, "hour").isoFieldsAtLocal().time().hour()));
        installGetter(proto, "minute",
                r -> new JsNumber(requireReceiver(r, "minute").isoFieldsAtLocal().time().minute()));
        installGetter(proto, "second",
                r -> new JsNumber(requireReceiver(r, "second").isoFieldsAtLocal().time().second()));
        installGetter(proto, "millisecond",
                r -> new JsNumber(requireReceiver(r, "millisecond").isoFieldsAtLocal().time().millisecond()));
        installGetter(proto, "microsecond",
                r -> new JsNumber(requireReceiver(r, "microsecond").isoFieldsAtLocal().time().microsecond()));
        installGetter(proto, "nanosecond",
                r -> new JsNumber(requireReceiver(r, "nanosecond").isoFieldsAtLocal().time().nanosecond()));
        installGetter(proto, "dayOfWeek",
                r -> new JsNumber(IsoCalendar.dayOfWeek(requireReceiver(r, "dayOfWeek").isoFieldsAtLocal().date())));
        installGetter(proto, "dayOfYear",
                r -> new JsNumber(IsoCalendar.dayOfYear(requireReceiver(r, "dayOfYear").isoFieldsAtLocal().date())));
        installGetter(proto, "weekOfYear",
                r -> new JsNumber(IsoCalendar.weekOfYear(requireReceiver(r, "weekOfYear").isoFieldsAtLocal().date())));
        installGetter(proto, "yearOfWeek",
                r -> new JsNumber(IsoCalendar.yearOfWeek(requireReceiver(r, "yearOfWeek").isoFieldsAtLocal().date())));
        installGetter(proto, "daysInWeek", r -> {
            requireReceiver(r, "daysInWeek");
            return new JsNumber(7);
        });
        installGetter(proto, "daysInMonth", r -> {
            final var d = requireReceiver(r, "daysInMonth").isoFieldsAtLocal().date();
            return new JsNumber(IsoCalendar.daysInMonth(d.year(), d.month()));
        });
        installGetter(proto, "daysInYear", r -> {
            final var d = requireReceiver(r, "daysInYear").isoFieldsAtLocal().date();
            return new JsNumber(IsoCalendar.daysInYear(d.year()));
        });
        installGetter(proto, "monthsInYear", r -> {
            requireReceiver(r, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", r -> {
            final var d = requireReceiver(r, "inLeapYear").isoFieldsAtLocal().date();
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
        installGetter(proto, "timeZoneId", r -> new JsString(requireReceiver(r, "timeZoneId").timeZoneId()));
        installGetter(proto, "epochMilliseconds",
                r -> new JsNumber(requireReceiver(r, "epochMilliseconds").epochMillisecondsLong()));
        installGetter(proto, "epochNanoseconds",
                r -> new JsBigInt(requireReceiver(r, "epochNanoseconds").epochNanoseconds()));
        installGetter(proto, "offsetNanoseconds", r -> new JsNumber(
                requireReceiver(r, "offsetNanoseconds").offset().getTotalSeconds() * 1_000_000_000.0));
        installGetter(proto, "offset",
                r -> new JsString(TemporalFormatter.formatOffset(requireReceiver(r, "offset").offset())));
        installGetter(proto, "hoursInDay", r -> new JsNumber(hoursInDay(requireReceiver(r, "hoursInDay"))));
    }

    private static double hoursInDay(JsTemporalZonedDateTime receiver) {
        final var zdt = receiver.toJavaZonedDateTime();
        final var startOfDay = zdt.toLocalDate().atStartOfDay(receiver.zone());
        final var startOfNextDay = zdt.toLocalDate().plusDays(1).atStartOfDay(receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfDay));
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfNextDay));
        final var nanos = epochNanosOf(startOfNextDay).subtract(epochNanosOf(startOfDay));
        return nanos.doubleValue() / 3_600_000_000_000.0;
    }

    private static JsValue startOfDay(JsTemporalZonedDateTime receiver) {
        final var localDate = receiver.toJavaZonedDateTime().toLocalDate();
        final var startOfDay = localDate.atStartOfDay(receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfDay));
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(startOfDay, receiver.timeZoneId());
    }

    private static JsTemporalZonedDateTime requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalZonedDateTime zdt) {
            return zdt;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.ZonedDateTime.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue getMethod(JsTemporalZonedDateTime receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0)));
            case "withTimeZone" ->
                new JsNativeFunction("withTimeZone", (_, args) -> withTimeZone(receiver, arg(args, 0)));
            case "withPlainDate" ->
                new JsNativeFunction("withPlainDate", (_, args) -> withPlainDate(receiver, arg(args, 0), ops));
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
            case "startOfDay" -> new JsNativeFunction("startOfDay", (_, _) -> startOfDay(receiver));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toInstant" -> new JsNativeFunction("toInstant", (_, _) -> receiver.toInstant());
            case "toPlainDate" -> new JsNativeFunction("toPlainDate", (_, _) -> toPlainDate(receiver));
            case "toPlainTime" -> new JsNativeFunction("toPlainTime", (_, _) -> toPlainTime(receiver));
            case "toPlainDateTime" -> new JsNativeFunction("toPlainDateTime", (_, _) -> toPlainDateTime(receiver));
            case "toPlainYearMonth" -> new JsNativeFunction("toPlainYearMonth", (_, _) -> toPlainYearMonth(receiver));
            case "toPlainMonthDay" -> new JsNativeFunction("toPlainMonthDay", (_, _) -> toPlainMonthDay(receiver));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "getTimeZoneTransition" -> new JsNativeFunction("getTimeZoneTransition",
                    (_, args) -> getTimeZoneTransition(receiver, arg(args, 0), ops));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.ZonedDateTime to a primitive value with valueOf; use compare() or "
                                + "equals() instead");
            });
            default -> null;
        };
    }

}
