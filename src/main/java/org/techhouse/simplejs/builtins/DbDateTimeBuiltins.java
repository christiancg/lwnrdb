package org.techhouse.simplejs.builtins;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.List;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * The {@code DbDateTime} global: constructor + prototype for the EJson {@code #datetime(...)} custom
 * type, shaped like {@link GeoBuiltins}. {@code toTemporal()} bridges to
 * {@code Temporal.PlainDateTime} so the calendar arithmetic already shipped is reachable from a
 * stored {@code datetime} field.
 */
public final class DbDateTimeBuiltins {
    public static final List<String> NAMES = List.of("toString", "toJSON", "toTemporal");
    public static final List<String> FIELD_ACCESSORS = List.of("year", "month", "day", "hour", "minute", "second");

    private static final int DEFAULT_YEAR = 1970;

    private DbDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("DbDateTime", (thisArg, args) -> {
            requireNewTarget(thisArg);
            return withNewTargetPrototype(new JsDbDateTime(construct(args, ops)), ops);
        });
        final var from = new JsNativeFunction("from",
                (_, args) -> new JsDbDateTime(toLocalDateTime(arg(args, 0), ops)));
        from.setLength(1);
        ctor.setProperty("from", from);
        return ctor;
    }

    private static void requireNewTarget(JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor DbDateTime requires 'new'");
        }
    }

    private static JsValue withNewTargetPrototype(JsDbDateTime constructed, InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (ops == null || newTarget == null || newTarget instanceof JsUndefined) {
            return constructed;
        }
        final var proto = ops.getMember(newTarget, new JsString("prototype"));
        if (!(proto instanceof JsObject requested) || proto == ops.getPrototypeOf(constructed)) {
            return constructed;
        }
        final var wrapper = new JsObject();
        wrapper.setPrimitive(constructed);
        wrapper.setProto(requested);
        return wrapper;
    }

    private static LocalDateTime construct(List<JsValue> args, InterpreterOps ops) {
        return of(intArg(args, 0, DEFAULT_YEAR, ops), intArg(args, 1, 1, ops), intArg(args, 2, 1, ops),
                intArg(args, 3, 0, ops), intArg(args, 4, 0, ops), intArg(args, 5, 0, ops));
    }

    private static LocalDateTime of(int year, int month, int day, int hour, int minute, int second) {
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid DbDateTime: " + e.getMessage());
        }
    }

    private static int intArg(List<JsValue> args, int index, int fallback, InterpreterOps ops) {
        final var value = arg(args, index);
        if (value instanceof JsUndefined) {
            return fallback;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException("DbDateTime field must be a finite number");
        }
        return (int) (number < 0 ? Math.ceil(number) : Math.floor(number));
    }

    // Accepts a DbDateTime, a Temporal.PlainDateTime/PlainDate, the "#datetime(...)" wire string or a
    // bare ISO-8601 local date-time string, or a {year, month, day, hour, minute, second} bag.
    private static LocalDateTime toLocalDateTime(JsValue value, InterpreterOps ops) {
        if (value instanceof JsDbDateTime dateTime) {
            return dateTime.getValue();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsDbDateTime wrapped) {
            return wrapped.getValue();
        }
        if (value instanceof JsTemporalPlainDateTime temporal) {
            return of(temporal.year(), temporal.month(), temporal.day(), temporal.time().hour(),
                    temporal.time().minute(), temporal.time().second());
        }
        if (value instanceof JsTemporalPlainDate temporal) {
            return of(temporal.year(), temporal.month(), temporal.day(), 0, 0, 0);
        }
        if (value instanceof JsString text) {
            return parse(text.getValue());
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return of(field(value, "year", DEFAULT_YEAR, ops), field(value, "month", 1, ops),
                    field(value, "day", 1, ops), field(value, "hour", 0, ops), field(value, "minute", 0, ops),
                    field(value, "second", 0, ops));
        }
        throw new TypeErrorException("Cannot convert to DbDateTime: expected a DbDateTime, an ISO date-time string or "
                + "a {year, month, day} object");
    }

    private static LocalDateTime parse(String text) {
        try {
            return text.startsWith("#") ? new JsonDateTime(text).getCustomValue() : LocalDateTime.parse(text);
        } catch (RuntimeException e) {
            throw new RangeErrorException("Invalid date-time string: '" + text + "'");
        }
    }

    private static int field(JsValue target, String name, int fallback, InterpreterOps ops) {
        final var value = member(target, name, ops);
        if (value instanceof JsUndefined) {
            return fallback;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite number");
        }
        return (int) (number < 0 ? Math.ceil(number) : Math.floor(number));
    }

    private static JsValue member(JsValue target, String name, InterpreterOps ops) {
        if (ops == null) {
            return target instanceof JsObject object ? object.get(name) : JsUndefined.getInstance();
        }
        return ops.getMember(target, new JsString(name));
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    public static void installAccessors(JsObject proto) {
        for (final var name : FIELD_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name,
                    (thisArg, _) -> fieldAccessor(requireReceiver(thisArg, name), name));
            getter.setLength(0);
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
        }
    }

    private static JsDbDateTime requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsDbDateTime dateTime) {
            return dateTime;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsDbDateTime wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("DbDateTime.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue fieldAccessor(JsDbDateTime receiver, String name) {
        return FIELD_ACCESSORS.contains(name) ? new JsNumber(component(receiver, name)) : null;
    }

    private static int component(JsDbDateTime receiver, String name) {
        final var value = receiver.getValue();
        return switch (name) {
            case "year" -> value.getYear();
            case "month" -> value.getMonthValue();
            case "day" -> value.getDayOfMonth();
            case "hour" -> value.getHour();
            case "minute" -> value.getMinute();
            default -> value.getSecond();
        };
    }

    public static JsValue getMethod(JsDbDateTime receiver, String name) {
        return switch (name) {
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(receiver.toString()));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toTemporal" -> new JsNativeFunction("toTemporal", (_, _) -> toTemporal(receiver));
            default -> null;
        };
    }

    private static JsValue toTemporal(JsDbDateTime receiver) {
        final var value = receiver.getValue();
        return new JsTemporalPlainDateTime(
                new Iso8601Fields(value.getYear(), value.getMonthValue(), value.getDayOfMonth()),
                new IsoTimeFields(value.getHour(), value.getMinute(), value.getSecond(), value.getNano() / 1_000_000,
                        value.getNano() / 1_000 % 1_000, value.getNano() % 1_000));
    }
}
