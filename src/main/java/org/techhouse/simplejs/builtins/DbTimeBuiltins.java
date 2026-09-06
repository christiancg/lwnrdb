package org.techhouse.simplejs.builtins;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.List;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.values.JsDbTime;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * The {@code DbTime} global: constructor + prototype for the EJson {@code #time(...)} custom type,
 * shaped like {@link GeoBuiltins}. {@code toTemporal()} bridges to {@code Temporal.PlainTime}.
 */
public final class DbTimeBuiltins {
    public static final List<String> NAMES = List.of("toString", "toJSON", "toTemporal");
    public static final List<String> FIELD_ACCESSORS = List.of("hour", "minute", "second");

    private DbTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("DbTime", (thisArg, args) -> {
            requireNewTarget(thisArg);
            return withNewTargetPrototype(new JsDbTime(construct(args, ops)), ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> new JsDbTime(toLocalTime(arg(args, 0), ops)));
        from.setLength(1);
        ctor.setProperty("from", from);
        return ctor;
    }

    private static void requireNewTarget(JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor DbTime requires 'new'");
        }
    }

    private static JsValue withNewTargetPrototype(JsDbTime constructed, InterpreterOps ops) {
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

    private static LocalTime construct(List<JsValue> args, InterpreterOps ops) {
        return of(intArg(args, 0, ops), intArg(args, 1, ops), intArg(args, 2, ops));
    }

    private static LocalTime of(int hour, int minute, int second) {
        try {
            return LocalTime.of(hour, minute, second);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid DbTime: " + e.getMessage());
        }
    }

    private static int intArg(List<JsValue> args, int index, InterpreterOps ops) {
        final var value = arg(args, index);
        if (value instanceof JsUndefined) {
            return 0;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException("DbTime field must be a finite number");
        }
        return (int) (number < 0 ? Math.ceil(number) : Math.floor(number));
    }

    // Accepts a DbTime, a Temporal.PlainTime, the "#time(...)" wire string or a bare ISO-8601 local
    // time string, or a {hour, minute, second} bag.
    private static LocalTime toLocalTime(JsValue value, InterpreterOps ops) {
        if (value instanceof JsDbTime time) {
            return time.getValue();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsDbTime wrapped) {
            return wrapped.getValue();
        }
        if (value instanceof JsTemporalPlainTime temporal) {
            final var fields = temporal.getFields();
            return of(fields.hour(), fields.minute(), fields.second());
        }
        if (value instanceof JsString text) {
            return parse(text.getValue());
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return of(field(value, "hour", ops), field(value, "minute", ops), field(value, "second", ops));
        }
        throw new TypeErrorException(
                "Cannot convert to DbTime: expected a DbTime, an ISO time string or a {hour, minute, second} object");
    }

    private static LocalTime parse(String text) {
        try {
            return text.startsWith("#") ? new JsonTime(text).getCustomValue() : LocalTime.parse(text);
        } catch (RuntimeException e) {
            throw new RangeErrorException("Invalid time string: '" + text + "'");
        }
    }

    private static int field(JsValue target, String name, InterpreterOps ops) {
        final var value = member(target, name, ops);
        if (value instanceof JsUndefined) {
            return 0;
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

    private static JsDbTime requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsDbTime time) {
            return time;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsDbTime wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("DbTime.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue fieldAccessor(JsDbTime receiver, String name) {
        return FIELD_ACCESSORS.contains(name) ? new JsNumber(component(receiver, name)) : null;
    }

    private static int component(JsDbTime receiver, String name) {
        final var value = receiver.getValue();
        return switch (name) {
            case "hour" -> value.getHour();
            case "minute" -> value.getMinute();
            default -> value.getSecond();
        };
    }

    public static JsValue getMethod(JsDbTime receiver, String name) {
        return switch (name) {
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(receiver.toString()));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toTemporal" -> new JsNativeFunction("toTemporal", (_, _) -> toTemporal(receiver));
            default -> null;
        };
    }

    private static JsValue toTemporal(JsDbTime receiver) {
        final var value = receiver.getValue();
        return new JsTemporalPlainTime(new IsoTimeFields(value.getHour(), value.getMinute(), value.getSecond(),
                value.getNano() / 1_000_000, value.getNano() / 1_000 % 1_000, value.getNano() % 1_000));
    }
}
