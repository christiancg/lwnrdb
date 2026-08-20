package org.techhouse.simplejs.builtins;

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
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.PlainDate}: an "iso8601"-calendar-only calendar date (see the feature plan's
 * scope-defining finding).
 */
public final class TemporalPlainDateBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "add", "subtract", "until", "since",
            "equals", "toPlainYearMonth", "toPlainMonthDay", "toPlainDateTime", "toZonedDateTime", "toString", "toJSON",
            "toLocaleString", "getISOFields", "valueOf");

    private TemporalPlainDateBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainDate", (_, args) -> {
            requireNewTarget();
            return construct(args, ops);
        });
        ctor.setLength(3);
        final var from = new JsNativeFunction("from", (_, args) -> toPlainDate(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> new JsNumber(IsoCalendar
                .compareIsoDate(toPlainDate(arg(args, 0), ops).fields(), toPlainDate(arg(args, 1), ops).fields())));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    public static JsValue getMethod(JsTemporalPlainDate receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0), ops));
            case "add" -> new JsNativeFunction("add", (_, args) -> add(receiver, arg(args, 0), arg(args, 1), ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> subtract(receiver, arg(args, 0), arg(args, 1), ops));
            case "until" ->
                new JsNativeFunction("until", (_, args) -> until(receiver, arg(args, 0), arg(args, 1), ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> since(receiver, arg(args, 0), arg(args, 1), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toPlainYearMonth" -> new JsNativeFunction("toPlainYearMonth", (_, _) -> toPlainYearMonth(receiver));
            case "toPlainMonthDay" -> new JsNativeFunction("toPlainMonthDay", (_, _) -> toPlainMonthDay(receiver));
            case "toPlainDateTime" ->
                new JsNativeFunction("toPlainDateTime", (_, args) -> toPlainDateTime(receiver, arg(args, 0), ops));
            case "toZonedDateTime" ->
                new JsNativeFunction("toZonedDateTime", (_, args) -> toZonedDateTime(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.PlainDate to a primitive value with valueOf; use compare() or "
                                + "equals() instead");
            });
            default -> null;
        };
    }

    // `disposed`-style accessor install (mirrors DisposableStackBuiltins.installAccessors): these are
    // real accessor properties on the shared prototype, brand-checked per-call since they are not
    // routed through Intrinsics' requireTemporalPlainDate resolver the way NAMES methods are.
    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", receiver -> new JsNumber(requireReceiver(receiver, "year").year()));
        installGetter(proto, "month", receiver -> new JsNumber(requireReceiver(receiver, "month").month()));
        installGetter(proto, "monthCode",
                receiver -> new JsString(monthCode(requireReceiver(receiver, "monthCode").month())));
        installGetter(proto, "day", receiver -> new JsNumber(requireReceiver(receiver, "day").day()));
        installGetter(proto, "dayOfWeek",
                receiver -> new JsNumber(IsoCalendar.dayOfWeek(requireReceiver(receiver, "dayOfWeek").fields())));
        installGetter(proto, "dayOfYear",
                receiver -> new JsNumber(IsoCalendar.dayOfYear(requireReceiver(receiver, "dayOfYear").fields())));
        installGetter(proto, "weekOfYear",
                receiver -> new JsNumber(IsoCalendar.weekOfYear(requireReceiver(receiver, "weekOfYear").fields())));
        installGetter(proto, "yearOfWeek",
                receiver -> new JsNumber(IsoCalendar.yearOfWeek(requireReceiver(receiver, "yearOfWeek").fields())));
        installGetter(proto, "daysInWeek", receiver -> {
            requireReceiver(receiver, "daysInWeek");
            return new JsNumber(7);
        });
        installGetter(proto, "daysInMonth", receiver -> {
            final var date = requireReceiver(receiver, "daysInMonth");
            return new JsNumber(IsoCalendar.daysInMonth(date.year(), date.month()));
        });
        installGetter(proto, "daysInYear", receiver -> {
            final var date = requireReceiver(receiver, "daysInYear");
            return new JsNumber(IsoCalendar.daysInYear(date.year()));
        });
        installGetter(proto, "monthsInYear", receiver -> {
            requireReceiver(receiver, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", receiver -> {
            final var date = requireReceiver(receiver, "inLeapYear");
            return JsBoolean.of(IsoCalendar.isLeapYear(date.year()));
        });
        installGetter(proto, "calendarId", receiver -> {
            requireReceiver(receiver, "calendarId");
            return new JsString("iso8601");
        });
        installGetter(proto, "era", receiver -> {
            requireReceiver(receiver, "era");
            return JsUndefined.getInstance();
        });
        installGetter(proto, "eraYear", receiver -> {
            requireReceiver(receiver, "eraYear");
            return JsUndefined.getInstance();
        });
    }

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }

    private static JsTemporalPlainDate requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainDate date) {
            return date;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDate wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("Temporal.PlainDate.prototype." + method + " called on an incompatible receiver");
    }

    // Unlike a bare global constructor (Map, Date, ...), `Temporal.PlainDate` is reached through a
    // MemberExpression call: `Temporal.PlainDate(...)` binds `thisArg` to the `Temporal` namespace
    // object per ordinary EvaluateCall semantics, so the thisArg-is-undefined signal every other
    // builtin's requireNewTarget relies on to tell a bare call apart from a subclass's super() call
    // does not hold here. new.target alone is authoritative for "was this reached via `new`" (it is
    // null for both a plain call and a super() call), so this narrows to that check - a documented,
    // narrow gap: `class X extends Temporal.PlainDate` super() calls are not supported.
    private static void requireNewTarget() {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.PlainDate requires 'new'");
        }
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
        final var year = toIntegerField(arg(args, 0), "year", ops);
        final var month = toIntegerField(arg(args, 1), "month", ops);
        final var day = toIntegerField(arg(args, 2), "day", ops);
        final var calendarArg = arg(args, 3);
        if (!(calendarArg instanceof JsUndefined)) {
            requireIso8601(JsCoercion.toStr(calendarArg, ops));
        }
        return new JsTemporalPlainDate(IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT));
    }

    private static void requireIso8601(String calendar) {
        if (!"iso8601".equals(calendar)) {
            throw new RangeErrorException(
                    "Only the \"iso8601\" calendar is supported by this engine, got: " + calendar);
        }
    }

    // ToTemporalDate: accepts an existing PlainDate, an ISO date string, or a date-like object
    // (year + month/monthCode + day, regulated per the `overflow` option).
    private static JsTemporalPlainDate toPlainDate(JsValue item, InterpreterOps ops) {
        return toPlainDate(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalPlainDate toPlainDate(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        final var overflow = readOverflowOption(optionsArg, ops);
        if (item instanceof JsTemporalPlainDate pd) {
            return new JsTemporalPlainDate(pd.fields());
        }
        if (item instanceof JsString s) {
            return new JsTemporalPlainDate(TemporalParser.parseDate(s.getValue()).date());
        }
        if (item instanceof JsObject obj) {
            return new JsTemporalPlainDate(dateFromFields(obj, overflow, ops));
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainDate");
    }

    private static Iso8601Fields dateFromFields(JsObject obj, RegulateOverflow overflow, InterpreterOps ops) {
        final var year = requiredIntegerField(obj, "year", ops);
        final var month = resolveMonth(obj, ops);
        final var day = requiredIntegerField(obj, "day", ops);
        return IsoCalendar.regulateDate(year, month, day, overflow);
    }

    private static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    private static int resolveMonth(JsObject obj, InterpreterOps ops) {
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

    // ToIntegerWithTruncation: a finite number is required, truncated toward zero (a genuinely
    // integral value, e.g. 12, passes through unchanged).
    private static int toIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        return (int) number;
    }

    private static String monthCode(int month) {
        return "M" + pad2(month);
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
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
        if ("auto".equals(raw)) {
            return fallback;
        }
        final var unit = requireDateUnit(raw, "largestUnit");
        return unit;
    }

    private static Unit readSmallestUnitOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "smallestUnit", ops);
        return value instanceof JsUndefined ? Unit.DAY : requireDateUnit(JsCoercion.toStr(value, ops), "smallestUnit");
    }

    private static Unit requireDateUnit(String raw, String optionName) {
        final var unit = Unit.parseTemporalUnit(raw);
        if (unit.ordinal() > Unit.DAY.ordinal()) {
            throw new RangeErrorException(
                    optionName + " must be one of \"year\", \"month\", \"week\" or \"day\" for Temporal.PlainDate, "
                            + "got: " + raw);
        }
        return unit;
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

    private static RoundingMode negateRoundingMode(RoundingMode mode) {
        return switch (mode) {
            case CEIL -> RoundingMode.FLOOR;
            case FLOOR -> RoundingMode.CEIL;
            case HALF_CEIL -> RoundingMode.HALF_FLOOR;
            case HALF_FLOOR -> RoundingMode.HALF_CEIL;
            default -> mode;
        };
    }

    private static void validateRoundingIncrement(long increment, Unit unit) {
        if (unit == Unit.DAY && increment != 1) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit day");
        }
    }

    private static TemporalFormatter.CalendarName readCalendarNameOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "calendarName", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.CalendarName.AUTO
                : TemporalFormatter.CalendarName.parse(JsCoercion.toStr(value, ops));
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

    private static JsValue with(JsTemporalPlainDate receiver, JsValue dateLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(dateLike)) {
            throw new TypeErrorException("Temporal.PlainDate.prototype.with argument must be an object");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var year = fieldOrDefault(dateLike, "year", receiver.year(), ops);
        final var month = resolveMonthWith(dateLike, receiver.month(), ops);
        final var day = fieldOrDefault(dateLike, "day", receiver.day(), ops);
        return new JsTemporalPlainDate(IsoCalendar.regulateDate(year, month, day, overflow));
    }

    private static int fieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : toIntegerField(value, name, ops);
    }

    private static int resolveMonthWith(JsValue obj, int defaultMonth, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            return parseMonthCode(JsCoercion.toStr(monthCodeValue, ops));
        }
        return fieldOrDefault(obj, "month", defaultMonth, ops);
    }

    // withCalendar is effectively an identity operation in ISO-only mode: the only calendar this
    // engine ever carries is "iso8601", so the only observable effect is validating the argument.
    private static JsValue withCalendar(JsTemporalPlainDate receiver, JsValue calendarArg, InterpreterOps ops) {
        requireIso8601(JsCoercion.toStr(calendarArg, ops));
        return new JsTemporalPlainDate(receiver.fields());
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsString s) {
            return TemporalParser.parseDuration(s.getValue());
        }
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Invalid Temporal.Duration-like value");
        }
        final var fields = new DurationFields(durationFieldOrZero(value, "years", ops),
                durationFieldOrZero(value, "months", ops), durationFieldOrZero(value, "weeks", ops),
                durationFieldOrZero(value, "days", ops), durationFieldOrZero(value, "hours", ops),
                durationFieldOrZero(value, "minutes", ops), durationFieldOrZero(value, "seconds", ops),
                durationFieldOrZero(value, "milliseconds", ops), durationFieldOrZero(value, "microseconds", ops),
                durationFieldOrZero(value, "nanoseconds", ops));
        DurationMath.sign(fields);
        return fields;
    }

    // ToIntegerIfIntegral: unlike the date fields above, a Duration-like field must already BE an
    // integer (no truncation) - 1.5 is a RangeError, not silently floored.
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

    // Only the date-granular fields (years/months/weeks/days) affect a PlainDate; a duration's time
    // units are validated (same-sign-or-zero, via toDurationFields) but otherwise not consumed - the
    // calendar's dateAdd operation never reads them either.
    private static JsValue add(JsTemporalPlainDate receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = toDurationFields(durationLike, ops);
        final var overflow = readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainDate(IsoCalendar.addDate(receiver.fields(), duration.years(), duration.months(),
                duration.weeks(), duration.days(), overflow));
    }

    private static JsValue subtract(JsTemporalPlainDate receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = negate(toDurationFields(durationLike, ops));
        final var overflow = readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainDate(IsoCalendar.addDate(receiver.fields(), duration.years(), duration.months(),
                duration.weeks(), duration.days(), overflow));
    }

    private static JsValue until(JsTemporalPlainDate receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalPlainDate receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    private static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    private static JsValue difference(JsTemporalPlainDate receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toPlainDate(otherArg, ops);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
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
        var fields = IsoCalendar.differenceISODate(receiver.fields(), other.fields(), largestUnit);
        if (smallestUnit != Unit.DAY || increment != 1) {
            validateRoundingIncrement(increment, smallestUnit);
            final var anchor = RelativeDurationMath.Anchor.plain(receiver.fields(), MIDNIGHT);
            fields = RelativeDurationMath.roundedDifference(anchor, other.fields(), MIDNIGHT, largestUnit, smallestUnit,
                    increment, mode);
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    private static JsValue equalsMethod(JsTemporalPlainDate receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainDate(otherArg, ops);
        return JsBoolean.of(receiver.sameDate(other));
    }

    private static JsValue toPlainYearMonth(JsTemporalPlainDate receiver) {
        return new JsTemporalPlainYearMonth(new Iso8601Fields(receiver.year(), receiver.month(), receiver.day()));
    }

    private static JsValue toPlainMonthDay(JsTemporalPlainDate receiver) {
        return new JsTemporalPlainMonthDay(new Iso8601Fields(receiver.year(), receiver.month(), receiver.day()));
    }

    private static JsValue toPlainDateTime(JsTemporalPlainDate receiver, JsValue timeLike, InterpreterOps ops) {
        final var time = extractTimeFields(timeLike, ops);
        return new JsTemporalPlainDateTime(receiver.fields(), time);
    }

    private static IsoTimeFields extractTimeFields(JsValue timeLike, InterpreterOps ops) {
        if (timeLike == null || timeLike instanceof JsUndefined) {
            return new IsoTimeFields(0, 0, 0, 0, 0, 0);
        }
        if (timeLike instanceof JsString s) {
            return TemporalParser.parseTime(s.getValue()).time();
        }
        if (!InterpreterUtils.isObjectLike(timeLike)) {
            throw new TypeErrorException("Invalid time-like value");
        }
        final var hour = timeFieldOrZero(timeLike, "hour", ops);
        final var minute = timeFieldOrZero(timeLike, "minute", ops);
        final var second = timeFieldOrZero(timeLike, "second", ops);
        final var millisecond = timeFieldOrZero(timeLike, "millisecond", ops);
        final var microsecond = timeFieldOrZero(timeLike, "microsecond", ops);
        final var nanosecond = timeFieldOrZero(timeLike, "nanosecond", ops);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static int timeFieldOrZero(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? 0 : toIntegerField(value, name, ops);
    }

    private static JsValue toZonedDateTime(JsTemporalPlainDate receiver, JsValue options, InterpreterOps ops) {
        final var timeZoneId = extractTimeZoneId(options, ops);
        final var plainTimeArg = options instanceof JsObject obj
                ? ops.getMember(obj, new JsString("plainTime"))
                : JsUndefined.getInstance();
        final var time = extractTimeFields(plainTimeArg, ops);
        final var zone = TemporalZonedDateTimeBuiltins.zoneOf(timeZoneId);
        return TemporalZonedDateTimeBuiltins.resolveToZoned(receiver.fields(), time, zone, timeZoneId,
                TemporalZonedDateTimeBuiltins.Disambiguation.COMPATIBLE);
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
        throw new TypeErrorException("Temporal.PlainDate.prototype.toZonedDateTime requires a timeZone");
    }

    private static JsValue toStringMethod(JsTemporalPlainDate receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        return new JsString(TemporalFormatter.formatDate(receiver.fields(), calendarName));
    }

    private static JsValue getISOFields(JsTemporalPlainDate receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.year()));
        return obj;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
