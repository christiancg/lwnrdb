package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeDifference.addOrSubtract;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeDifference.difference;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeFormat.toStringMethod;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeFrom.compareStatic;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeFrom.from;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeFrom.isTemporalInstance;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeFrom.toTemporalTime;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeRounding.round;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.regulateTime;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalPlainTimeBuiltins {
    public static final List<String> NAMES = List.of("with", "add", "subtract", "until", "since", "round", "equals",
            "toString", "toJSON", "toLocaleString", "toPlainDateTime", "toZonedDateTime", "getISOFields", "valueOf");
    public static final List<String> FIELD_ACCESSORS = Unit.TIME_UNIT_SINGULARS;

    public static final List<String> FIELD_NAMES = Unit.TIME_UNIT_SINGULARS;
    public static final List<Integer> ALPHABETICAL_FIELD_ORDER = IntStream.range(0, FIELD_NAMES.size()).boxed()
            .sorted(Comparator.comparing(FIELD_NAMES::get)).toList();

    private TemporalPlainTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainTime", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.PlainTime", thisArg);
            return withNewTargetPrototype(new JsTemporalPlainTime(constructFields(args, ops)), ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> from(args, ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> compareStatic(args, ops));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    private static IsoTimeFields constructFields(List<JsValue> args, InterpreterOps ops) {
        final var values = new int[6];
        for (var i = 0; i < FIELD_NAMES.size(); i++) {
            values[i] = intArg(args, i, ops);
        }
        return regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], RegulateOverflow.REJECT);
    }

    private static int intArg(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return 0;
        }
        return (int) toIntegerWithTruncation(args.get(index), ops);
    }

    public static double toIntegerWithTruncation(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException("Invalid Temporal.PlainTime field: must be a finite number");
        }
        return number < 0 ? Math.ceil(number) : Math.floor(number);
    }

    public static JsValue fieldAccessor(JsTemporalPlainTime receiver, String name) {
        final var fields = receiver.getFields();
        return switch (name) {
            case "hour" -> new JsNumber(fields.hour());
            case "minute" -> new JsNumber(fields.minute());
            case "second" -> new JsNumber(fields.second());
            case "millisecond" -> new JsNumber(fields.millisecond());
            case "microsecond" -> new JsNumber(fields.microsecond());
            case "nanosecond" -> new JsNumber(fields.nanosecond());
            default -> null;
        };
    }

    public static JsValue getMethod(JsTemporalPlainTime receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "add" -> new JsNativeFunction("add", (_, args) -> addOrSubtract(receiver, arg(args, 0), 1, ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> addOrSubtract(receiver, arg(args, 0), -1, ops));
            case "until" -> new JsNativeFunction("until",
                    (_, args) -> difference(receiver, arg(args, 0), arg(args, 1), false, ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> difference(receiver, arg(args, 0), arg(args, 1), true, ops));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "toPlainDateTime" ->
                new JsNativeFunction("toPlainDateTime", (_, args) -> toPlainDateTime(receiver, arg(args, 0), ops));
            case "toZonedDateTime" -> new JsNativeFunction("toZonedDateTime", (_, _) -> {
                throw new TypeErrorException("Temporal.PlainTime.prototype.toZonedDateTime is not yet supported: "
                        + "Temporal.Instant/Temporal.ZonedDateTime land in a later phase");
            });
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getIsoFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException("Cannot convert a Temporal.PlainTime to a primitive value");
            });
            default -> null;
        };
    }

    public static JsValue member(JsValue target, String name, InterpreterOps ops) {
        if (ops == null) {
            return target instanceof JsObject object ? object.get(name) : JsUndefined.getInstance();
        }
        return ops.getMember(target, new JsString(name));
    }

    private static JsValue with(JsTemporalPlainTime receiver, JsValue timeLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(timeLike) || isTemporalInstance(timeLike)) {
            throw new TypeErrorException("with() argument must be a plain time-like object");
        }
        if (!(member(timeLike, "calendar", ops) instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a calendar property");
        }
        if (!(member(timeLike, "timeZone", ops) instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a timeZone property");
        }
        final var current = receiver.getFields();
        final var values = new int[]{current.hour(), current.minute(), current.second(), current.millisecond(),
                current.microsecond(), current.nanosecond()};
        var any = false;
        for (final var idx : ALPHABETICAL_FIELD_ORDER) {
            final var value = member(timeLike, FIELD_NAMES.get(idx), ops);
            if (!(value instanceof JsUndefined)) {
                any = true;
                values[idx] = (int) toIntegerWithTruncation(value, ops);
            }
        }
        if (!any) {
            throw new TypeErrorException("Invalid Temporal.PlainTime-like object: no recognized properties");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainTime(
                regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], overflow));
    }

    public static IsoTimeFields fromNanosOfDay(long nanos) {
        var remaining = nanos;
        final var hour = (int) (remaining / 3_600_000_000_000L);
        remaining %= 3_600_000_000_000L;
        final var minute = (int) (remaining / 60_000_000_000L);
        remaining %= 60_000_000_000L;
        final var second = (int) (remaining / 1_000_000_000L);
        remaining %= 1_000_000_000L;
        final var millisecond = (int) (remaining / 1_000_000L);
        remaining %= 1_000_000L;
        final var microsecond = (int) (remaining / 1_000L);
        final var nanosecond = (int) (remaining % 1_000L);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static JsValue equalsMethod(JsTemporalPlainTime receiver, JsValue other, InterpreterOps ops) {
        return JsBoolean.of(JsTemporalPlainTime.compare(receiver, toTemporalTime(other, ops)) == 0);
    }

    private static JsValue toPlainDateTime(JsTemporalPlainTime receiver, JsValue dateLike, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(dateLike)) {
            throw new TypeErrorException("toPlainDateTime requires a date-like object with year, month and day");
        }
        final var year = (int) toIntegerWithTruncation(requireMember(dateLike, "year", ops), ops);
        final var month = (int) toIntegerWithTruncation(requireMember(dateLike, "month", ops), ops);
        final var day = (int) toIntegerWithTruncation(requireMember(dateLike, "day", ops), ops);
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT);
        return new JsTemporalPlainDateTime(date, receiver.getFields());
    }

    private static JsValue requireMember(JsValue obj, String name, InterpreterOps ops) {
        final var value = member(obj, name, ops);
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return value;
    }

    private static JsValue getIsoFields(JsTemporalPlainTime receiver) {
        final var fields = receiver.getFields();
        final var result = new JsObject();
        result.set("isoHour", new JsNumber(fields.hour()));
        result.set("isoMinute", new JsNumber(fields.minute()));
        result.set("isoSecond", new JsNumber(fields.second()));
        result.set("isoMillisecond", new JsNumber(fields.millisecond()));
        result.set("isoMicrosecond", new JsNumber(fields.microsecond()));
        result.set("isoNanosecond", new JsNumber(fields.nanosecond()));
        return result;
    }
}
