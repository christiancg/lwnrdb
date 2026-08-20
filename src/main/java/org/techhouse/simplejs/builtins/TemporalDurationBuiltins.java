package org.techhouse.simplejs.builtins;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
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
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.Duration}: ten signed fields with no calendar dependency. Balancing/rounding
 * across years, months or weeks needs a {@code relativeTo} date to resolve (a "month" has no fixed
 * length) and is out of scope for this phase - {@link DurationMath} only implements the
 * calendar-independent part (day and below), so every operation that would need calendar-aware
 * balancing throws a {@link RangeErrorException} documenting the gap instead of guessing.
 */
public final class TemporalDurationBuiltins {
    private static final String CALENDAR_LIMITATION = "Duration operations involving years, months or weeks "
            + "require calendar-aware balancing (a relativeTo date), which is not implemented";

    private static final List<String> FIELD_ORDER = List.of("years", "months", "weeks", "days", "hours", "minutes",
            "seconds", "milliseconds", "microseconds", "nanoseconds");

    public static final List<String> METHOD_NAMES = List.of("with", "negated", "abs", "add", "subtract", "round",
            "total", "toString", "toJSON", "toLocaleString", "valueOf");

    // Per-instance field access is an accessor property (a getter), not a method - installed on the
    // prototype via JsObject.defineAccessor/PropertyFlags, mirroring how e.g. regex flag accessors
    // are installed, rather than through the generic method-dispatch wrapper every other name here
    // goes through.
    public static final List<String> ACCESSOR_NAMES = List.of("years", "months", "weeks", "days", "hours", "minutes",
            "seconds", "milliseconds", "microseconds", "nanoseconds", "sign", "blank");

    public static final List<String> NAMES = concat(METHOD_NAMES, ACCESSOR_NAMES);

    private TemporalDurationBuiltins() {
    }

    private static List<String> concat(List<String> a, List<String> b) {
        final var combined = new ArrayList<String>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return List.copyOf(combined);
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Duration", (thisArg, args) -> {
            requireNewTarget(thisArg);
            return construct(args, ops);
        });
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> from(arg(args, 0), ops)));
        ctor.setProperty("compare", new JsNativeFunction("compare", (_, args) -> compare(args, ops)));
        return ctor;
    }

    // Unlike Map/Date/DisposableStack (always reached as a bare global identifier, so a plain call's
    // thisArg is reliably undefined), Temporal.Duration only ever exists as a member of the Temporal
    // namespace object - so a plain `Temporal.Duration()` call's thisArg is that namespace object, not
    // undefined, and the MapBuiltins-style "thisArg is not undefined" check alone would wrongly accept
    // it. A genuine subclass super() call is told apart instead by instance provenance: ClassEvaluator
    // stamps the under-construction instance's klass before running any super constructor (see
    // JsClass.construct), which a plain object such as the Temporal namespace never carries.
    private static void requireNewTarget(JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget != null && !(newTarget instanceof JsUndefined)) {
            return;
        }
        if (thisArg instanceof JsObject object && object.getKlass() != null) {
            return;
        }
        throw new TypeErrorException("Constructor Temporal.Duration requires 'new'");
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
        final var values = new double[FIELD_ORDER.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = integerField(args, i, ops);
        }
        final var fields = toFields(values);
        DurationMath.sign(fields);
        return new JsTemporalDuration(fields);
    }

    private static DurationFields toFields(double[] v) {
        return new DurationFields(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }

    private static double integerField(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size()) {
            return 0;
        }
        final var value = args.get(index);
        if (value instanceof JsUndefined) {
            return 0;
        }
        return integerValue(value, ops);
    }

    private static double integerValue(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (!Double.isFinite(number)) {
            throw new RangeErrorException("Duration field must be a finite integer, got " + number);
        }
        if (number != Math.floor(number)) {
            throw new RangeErrorException("Duration field must be an integer, got " + number);
        }
        return number;
    }

    private static JsValue from(JsValue item, InterpreterOps ops) {
        if (item instanceof JsTemporalDuration duration) {
            return new JsTemporalDuration(duration.getFields());
        }
        if (item instanceof JsString str) {
            final var fields = TemporalParser.parseDuration(str.getValue());
            DurationMath.sign(fields);
            return new JsTemporalDuration(fields);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return new JsTemporalDuration(durationLikeFields(item, ops));
        }
        throw new TypeErrorException(
                "Temporal.Duration.from requires a Temporal.Duration, an ISO 8601 duration string, "
                        + "or a duration-like object");
    }

    private static DurationFields durationLikeFields(JsValue item, InterpreterOps ops) {
        final var values = new double[FIELD_ORDER.size()];
        var any = false;
        for (var i = 0; i < FIELD_ORDER.size(); i++) {
            final var member = ops.getMember(item, new JsString(FIELD_ORDER.get(i)));
            if (member == null || member instanceof JsUndefined) {
                continue;
            }
            any = true;
            values[i] = integerValue(member, ops);
        }
        if (!any) {
            throw new TypeErrorException(
                    "Invalid duration-like object: at least one of years/months/weeks/days/hours/minutes/seconds/"
                            + "milliseconds/microseconds/nanoseconds must be present");
        }
        final var fields = toFields(values);
        DurationMath.sign(fields);
        return fields;
    }

    // Two durations with literally identical fields are equal without needing a relativeTo, even if
    // both carry a year/month/week component - no calendar math is required to know a duration equals
    // itself. Only a genuine difference in a calendar-dependent field forces the (unimplemented)
    // relativeTo path, matching the narrow, non-speculative scope for calendar-dependent comparison.
    private static JsValue compare(List<JsValue> args, InterpreterOps ops) {
        final var one = toDurationFields(arg(args, 0), ops);
        final var two = toDurationFields(arg(args, 1), ops);
        if (one.equals(two)) {
            return new JsNumber(0);
        }
        try {
            DurationMath.requireCalendarIndependent(one, Unit.DAY);
            DurationMath.requireCalendarIndependent(two, Unit.DAY);
        } catch (UnsupportedOperationException e) {
            throw new RangeErrorException(CALENDAR_LIMITATION);
        }
        final var totalOne = DurationMath.totalNanoseconds(one);
        final var totalTwo = DurationMath.totalNanoseconds(two);
        return new JsNumber(totalOne.compareTo(totalTwo));
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString str) {
            final var fields = TemporalParser.parseDuration(str.getValue());
            DurationMath.sign(fields);
            return fields;
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return durationLikeFields(value, ops);
        }
        throw new TypeErrorException(
                "Expected a Temporal.Duration, an ISO 8601 duration string, or a duration-like object");
    }

    public static JsValue getMethod(JsTemporalDuration receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), ops));
            case "negated" -> new JsNativeFunction("negated", (_, _) -> negated(receiver));
            case "abs" -> new JsNativeFunction("abs", (_, _) -> abs(receiver));
            case "add" -> new JsNativeFunction("add", (_, args) -> addOrSubtract(receiver, arg(args, 0), ops, false));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> addOrSubtract(receiver, arg(args, 0), ops, true));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "total" -> new JsNativeFunction("total", (_, args) -> total(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException("Cannot convert a Temporal.Duration to a primitive value");
            });
            default -> null;
        };
    }

    // The 12 accessor getters (10 fields + sign + blank), dispatched from Intrinsics' per-name
    // accessor loop rather than getMethod above.
    public static JsValue fieldAccessor(JsTemporalDuration receiver, String name) {
        final var f = receiver.getFields();
        return switch (name) {
            case "years" -> new JsNumber(f.years());
            case "months" -> new JsNumber(f.months());
            case "weeks" -> new JsNumber(f.weeks());
            case "days" -> new JsNumber(f.days());
            case "hours" -> new JsNumber(f.hours());
            case "minutes" -> new JsNumber(f.minutes());
            case "seconds" -> new JsNumber(f.seconds());
            case "milliseconds" -> new JsNumber(f.milliseconds());
            case "microseconds" -> new JsNumber(f.microseconds());
            case "nanoseconds" -> new JsNumber(f.nanoseconds());
            case "sign" -> new JsNumber(receiver.sign());
            case "blank" -> JsBoolean.of(receiver.blank());
            default -> JsUndefined.getInstance();
        };
    }

    private static JsValue with(JsTemporalDuration receiver, JsValue durationLike, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(durationLike)) {
            throw new TypeErrorException("Temporal.Duration.prototype.with requires a duration-like object");
        }
        final var current = receiver.getFields();
        final var currentValues = new double[]{current.years(), current.months(), current.weeks(), current.days(),
                current.hours(), current.minutes(), current.seconds(), current.milliseconds(), current.microseconds(),
                current.nanoseconds()};
        for (var i = 0; i < FIELD_ORDER.size(); i++) {
            final var member = ops.getMember(durationLike, new JsString(FIELD_ORDER.get(i)));
            if (member != null && !(member instanceof JsUndefined)) {
                currentValues[i] = integerValue(member, ops);
            }
        }
        final var fields = toFields(currentValues);
        DurationMath.sign(fields);
        return new JsTemporalDuration(fields);
    }

    private static JsValue negated(JsTemporalDuration receiver) {
        final var f = receiver.getFields();
        return new JsTemporalDuration(new DurationFields(-f.years(), -f.months(), -f.weeks(), -f.days(), -f.hours(),
                -f.minutes(), -f.seconds(), -f.milliseconds(), -f.microseconds(), -f.nanoseconds()));
    }

    private static JsValue abs(JsTemporalDuration receiver) {
        final var f = receiver.getFields();
        return new JsTemporalDuration(new DurationFields(Math.abs(f.years()), Math.abs(f.months()), Math.abs(f.weeks()),
                Math.abs(f.days()), Math.abs(f.hours()), Math.abs(f.minutes()), Math.abs(f.seconds()),
                Math.abs(f.milliseconds()), Math.abs(f.microseconds()), Math.abs(f.nanoseconds())));
    }

    // AddDurations, narrowed to the calendar-independent case. The two operands' nanosecond totals
    // are summed (not their raw fields - a signed per-field sum like {days:1} + {hours:-1} can
    // legitimately produce mixed-sign fields before carrying, which balanceDuration's own uniform-
    // sign check would reject) and the signed total is then decomposed back into fields. Any nonzero
    // year/month/week on either operand needs a relativeTo date to resolve and is rejected instead of
    // guessed.
    private static JsValue addOrSubtract(JsTemporalDuration receiver, JsValue otherArg, InterpreterOps ops,
            boolean subtract) {
        final var a = receiver.getFields();
        final var other = toDurationFields(otherArg, ops);
        try {
            DurationMath.requireCalendarIndependent(a, Unit.DAY);
            DurationMath.requireCalendarIndependent(other, Unit.DAY);
            final var otherTotal = DurationMath.totalNanoseconds(other);
            final var totalNanos = DurationMath.totalNanoseconds(a).add(subtract ? otherTotal.negate() : otherTotal);
            return new JsTemporalDuration(DurationMath.balanceFromTotalNanoseconds(totalNanos, Unit.DAY));
        } catch (UnsupportedOperationException e) {
            throw new RangeErrorException(CALENDAR_LIMITATION);
        }
    }

    private static JsValue round(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Duration.prototype.round requires an options argument");
        }
        final var options = optionsArg instanceof JsString unit ? singleKeyOptions("smallestUnit", unit) : optionsArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a unit string");
        }
        final var smallestUnitValue = ops.getMember(options, new JsString("smallestUnit"));
        final var largestUnitValue = ops.getMember(options, new JsString("largestUnit"));
        final var incrementValue = ops.getMember(options, new JsString("roundingIncrement"));
        final var modeValue = ops.getMember(options, new JsString("roundingMode"));
        if (isAbsent(smallestUnitValue) && isAbsent(largestUnitValue)) {
            throw new RangeErrorException("round requires at least one of smallestUnit or largestUnit");
        }
        final var smallestUnit = isAbsent(smallestUnitValue)
                ? Unit.NANOSECOND
                : Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        final var largestUnit = isAbsent(largestUnitValue)
                ? Unit.DAY
                : Unit.parseTemporalUnit(JsCoercion.toStr(largestUnitValue, ops));
        final var increment = isAbsent(incrementValue) ? 1 : (long) integerValue(incrementValue, ops);
        final var mode = isAbsent(modeValue)
                ? RoundingMode.HALF_EXPAND
                : RoundingMode.parse(JsCoercion.toStr(modeValue, ops));
        try {
            return new JsTemporalDuration(
                    DurationMath.roundDuration(receiver.getFields(), smallestUnit, increment, mode, largestUnit));
        } catch (UnsupportedOperationException e) {
            throw new RangeErrorException(CALENDAR_LIMITATION);
        }
    }

    private static JsValue total(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Duration.prototype.total requires an options argument");
        }
        final var options = optionsArg instanceof JsString unit ? singleKeyOptions("unit", unit) : optionsArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a unit string");
        }
        final var unitValue = ops.getMember(options, new JsString("unit"));
        if (isAbsent(unitValue)) {
            throw new RangeErrorException("total requires a unit option");
        }
        final var unit = Unit.parseTemporalUnit(JsCoercion.toStr(unitValue, ops));
        final var fields = receiver.getFields();
        try {
            DurationMath.requireCalendarIndependent(fields, unit);
        } catch (UnsupportedOperationException e) {
            throw new RangeErrorException(CALENDAR_LIMITATION);
        }
        final var totalNanos = new BigDecimal(DurationMath.totalNanoseconds(fields));
        final var perUnit = new BigDecimal(DurationMath.nanosPerUnit(unit));
        return new JsNumber(totalNanos.divide(perUnit, 20, java.math.RoundingMode.HALF_UP).doubleValue());
    }

    private static JsValue toStringMethod(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (isAbsent(optionsArg)) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("Temporal.Duration.prototype.toString options must be an object");
        }
        final var smallestUnitValue = ops.getMember(optionsArg, new JsString("smallestUnit"));
        final var roundingModeValue = ops.getMember(optionsArg, new JsString("roundingMode"));
        final var digitsValue = ops.getMember(optionsArg, new JsString("fractionalSecondDigits"));
        var toFormat = receiver.getFields();
        Integer fractionalSecondDigits = null;
        if (!isAbsent(smallestUnitValue)) {
            final var unit = parseFractionalUnit(JsCoercion.toStr(smallestUnitValue, ops));
            final var mode = isAbsent(roundingModeValue)
                    ? RoundingMode.TRUNC
                    : RoundingMode.parse(JsCoercion.toStr(roundingModeValue, ops));
            try {
                toFormat = DurationMath.roundDuration(toFormat, unit, 1, mode, Unit.DAY);
            } catch (UnsupportedOperationException e) {
                throw new RangeErrorException(CALENDAR_LIMITATION);
            }
            fractionalSecondDigits = digitsForUnit(unit);
        } else if (!isAbsent(digitsValue)) {
            fractionalSecondDigits = parseFractionalDigits(digitsValue, ops);
        }
        return new JsString(TemporalFormatter.formatDuration(toFormat, fractionalSecondDigits));
    }

    private static Unit parseFractionalUnit(String value) {
        final var unit = Unit.parseTemporalUnit(value);
        if (unit != Unit.SECOND && unit != Unit.MILLISECOND && unit != Unit.MICROSECOND && unit != Unit.NANOSECOND) {
            throw new RangeErrorException(
                    "smallestUnit must be one of seconds/milliseconds/microseconds/nanoseconds, got " + value);
        }
        return unit;
    }

    private static int digitsForUnit(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            default -> 9;
        };
    }

    private static Integer parseFractionalDigits(JsValue value, InterpreterOps ops) {
        if (value instanceof JsString str && "auto".equals(str.getValue())) {
            return null;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (!Double.isFinite(number) || number != Math.floor(number) || number < 0 || number > 9) {
            throw new RangeErrorException("fractionalSecondDigits must be 0-9 or \"auto\", got " + number);
        }
        return (int) number;
    }

    private static JsObject singleKeyOptions(String key, JsString value) {
        final var options = new JsObject();
        options.set(key, value);
        return options;
    }

    private static boolean isAbsent(JsValue value) {
        return value == null || value instanceof JsUndefined;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
