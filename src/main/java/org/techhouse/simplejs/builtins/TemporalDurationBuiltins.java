package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.DurationRelativeTo.toRelativeToAnchor;
import static org.techhouse.simplejs.builtins.temporal.DurationRounding.round;
import static org.techhouse.simplejs.builtins.temporal.DurationRounding.tailLargestUnitForAdd;
import static org.techhouse.simplejs.builtins.temporal.DurationRounding.toStringMethod;
import static org.techhouse.simplejs.builtins.temporal.DurationRounding.total;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.DurationStringParser;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalDurationBuiltins {
    public static final String RELATIVE_TO_REQUIRED = "relativeTo is required to round/total/compare a "
            + "Temporal.Duration with years, months or weeks, or a unit larger than days";

    private static final List<String> FIELD_ORDER = Unit.PLURAL_NAMES;

    private static final List<String> PROPERTY_READ_ORDER = FIELD_ORDER.stream().sorted().toList();

    public static final List<String> METHOD_NAMES = List.of("with", "negated", "abs", "add", "subtract", "round",
            "total", "toString", "toJSON", "toLocaleString", "valueOf");

    public static final List<String> ACCESSOR_NAMES = Stream.concat(FIELD_ORDER.stream(), Stream.of("sign", "blank"))
            .toList();

    public static final List<String> NAMES = methodAndAccessorNames();

    private TemporalDurationBuiltins() {
    }

    private static List<String> methodAndAccessorNames() {
        final var combined = new ArrayList<String>(METHOD_NAMES.size() + ACCESSOR_NAMES.size());
        combined.addAll(METHOD_NAMES);
        combined.addAll(ACCESSOR_NAMES);
        return List.copyOf(combined);
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Duration", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.Duration", thisArg);
            return construct(args, ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> from(arg(args, 0), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> compare(args, ops));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
        final var values = new double[FIELD_ORDER.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = integerField(args, i, ops);
        }
        final var fields = toFields(values);
        return withNewTargetPrototype(new JsTemporalDuration(fields), ops);
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
        return number == 0.0 ? 0.0 : number;
    }

    private static JsValue from(JsValue item, InterpreterOps ops) {
        if (item instanceof JsTemporalDuration duration) {
            return new JsTemporalDuration(duration.getFields());
        }
        if (item instanceof JsString str) {
            final var fields = DurationStringParser.parseDuration(str.getValue());
            DurationMath.validate(fields);
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
        for (final var name : PROPERTY_READ_ORDER) {
            final var member = ops.getMember(item, new JsString(name));
            if (member == null || member instanceof JsUndefined) {
                continue;
            }
            any = true;
            values[FIELD_ORDER.indexOf(name)] = integerValue(member, ops);
        }
        if (!any) {
            throw new TypeErrorException(
                    "Invalid duration-like object: at least one of years/months/weeks/days/hours/minutes/seconds/"
                            + "milliseconds/microseconds/nanoseconds must be present");
        }
        final var fields = toFields(values);
        DurationMath.validate(fields);
        return fields;
    }

    private static JsValue compare(List<JsValue> args, InterpreterOps ops) {
        final var one = toDurationFields(arg(args, 0), ops);
        final var two = toDurationFields(arg(args, 1), ops);
        final var optionsArg = arg(args, 2);
        final var relativeToValue = optionOrUndefined(optionsArg, "relativeTo", ops);
        final var anchor = relativeToValue instanceof JsUndefined ? null : toRelativeToAnchor(relativeToValue, ops);
        if (one.equals(two)) {
            return new JsNumber(0);
        }
        if (anchor != null) {
            return new JsNumber(RelativeDurationMath.compareApplied(anchor, one, two, RegulateOverflow.CONSTRAIN));
        }
        if (hasCalendarUnits(one) || hasCalendarUnits(two)) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
        final var totalOne = DurationMath.totalNanoseconds(one);
        final var totalTwo = DurationMath.totalNanoseconds(two);
        return new JsNumber(totalOne.compareTo(totalTwo));
    }

    public static boolean hasCalendarUnits(DurationFields f) {
        return f.years() != 0 || f.months() != 0 || f.weeks() != 0;
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString str) {
            final var fields = DurationStringParser.parseDuration(str.getValue());
            DurationMath.validate(fields);
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
        var anyPresent = false;
        for (final var name : PROPERTY_READ_ORDER) {
            final var member = ops.getMember(durationLike, new JsString(name));
            if (member != null && !(member instanceof JsUndefined)) {
                currentValues[FIELD_ORDER.indexOf(name)] = integerValue(member, ops);
                anyPresent = true;
            }
        }
        if (!anyPresent) {
            throw new TypeErrorException("Duration-like object must contain at least one recognized property");
        }
        return new JsTemporalDuration(toFields(currentValues));
    }

    private static JsValue negated(JsTemporalDuration receiver) {
        return new JsTemporalDuration(DurationMath.negate(receiver.getFields()));
    }

    private static JsValue abs(JsTemporalDuration receiver) {
        final var f = receiver.getFields();
        return new JsTemporalDuration(new DurationFields(Math.abs(f.years()), Math.abs(f.months()), Math.abs(f.weeks()),
                Math.abs(f.days()), Math.abs(f.hours()), Math.abs(f.minutes()), Math.abs(f.seconds()),
                Math.abs(f.milliseconds()), Math.abs(f.microseconds()), Math.abs(f.nanoseconds())));
    }

    private static JsValue addOrSubtract(JsTemporalDuration receiver, JsValue otherArg, InterpreterOps ops,
            boolean subtract) {
        final var a = receiver.getFields();
        final var other = toDurationFields(otherArg, ops);
        try {
            DurationMath.requireCalendarIndependent(a, Unit.DAY);
            DurationMath.requireCalendarIndependent(other, Unit.DAY);
            final var otherTotal = DurationMath.totalNanoseconds(other);
            final var totalNanos = DurationMath.totalNanoseconds(a).add(subtract ? otherTotal.negate() : otherTotal);
            final var largestOrdinal = Math.min(tailLargestUnitForAdd(a).ordinal(),
                    tailLargestUnitForAdd(other).ordinal());
            return new JsTemporalDuration(
                    DurationMath.balanceFromTotalNanoseconds(totalNanos, Unit.values()[largestOrdinal]));
        } catch (UnsupportedOperationException e) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
    }

    public static boolean isAbsent(JsValue value) {
        return value == null || value instanceof JsUndefined;
    }

}
