package org.techhouse.simplejs.builtins;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsValue;

public final class DateBuiltins {
    public static final List<String> NAMES = List.of("getTime", "valueOf", "setTime", "toISOString", "toJSON",
            "toString", "toDateString", "toUTCString", "toLocaleString", "toLocaleDateString", "toLocaleTimeString",
            "getTimezoneOffset", "getFullYear", "getUTCFullYear", "getMonth", "getUTCMonth", "getDate", "getUTCDate",
            "getDay", "getUTCDay", "getHours", "getUTCHours", "getMinutes", "getUTCMinutes", "getSeconds",
            "getUTCSeconds", "getMilliseconds", "getUTCMilliseconds", "setFullYear", "setUTCFullYear", "setMonth",
            "setUTCMonth", "setDate", "setUTCDate", "setHours", "setUTCHours", "setMinutes", "setUTCMinutes",
            "setSeconds", "setUTCSeconds", "setMilliseconds", "setUTCMilliseconds");

    private static final double MS_PER_SECOND = 1000;
    private static final double MS_PER_MINUTE = 60_000;
    private static final double MS_PER_HOUR = 3_600_000;
    private static final double MS_PER_DAY = 86_400_000;
    private static final double MAX_TIME = 8.64e15;
    private static final double MAX_YEAR = 400_000;

    private DateBuiltins() {
    }

    public static JsNativeFunction create() {
        final var date = new JsNativeFunction("Date", (_, args) -> new JsDate(construct(args)));
        date.setProperty("now", new JsNativeFunction("now", (_, _) -> new JsNumber(System.currentTimeMillis())));
        date.setProperty("parse", new JsNativeFunction("parse", (_, args) -> new JsNumber(parse(str(args)))));
        date.setProperty("UTC", new JsNativeFunction("UTC", (_, args) -> new JsNumber(fromComponents(args))));
        return date;
    }

    private static double construct(List<JsValue> args) {
        if (args.isEmpty()) {
            return System.currentTimeMillis();
        }
        if (args.size() == 1) {
            final var arg = args.getFirst();
            return timeClip(switch (arg) {
                case JsDate date -> date.getTime();
                case JsString s -> parse(s.getValue());
                default -> JsCoercion.toNumber(arg);
            });
        }
        return fromComponents(args);
    }

    private static double fromComponents(List<JsValue> args) {
        var year = doubleArg(args, 0, Double.NaN);
        // The 1900 offset keys off the *truncated* year, so -0.999999 still maps to 1900.
        final var truncatedYear = truncate(year);
        if (!Double.isNaN(year) && truncatedYear >= 0 && truncatedYear <= 99) {
            year = 1900 + truncatedYear;
        }
        final var day = makeDay(year, doubleArg(args, 1, 0), doubleArg(args, 2, 1));
        final var time = makeTime(doubleArg(args, 3, 0), doubleArg(args, 4, 0), doubleArg(args, 5, 0),
                doubleArg(args, 6, 0));
        return timeClip(makeDate(day, time));
    }

    private static double parse(String value) {
        final var trimmed = value.strip();
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return java.time.LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            return Double.NaN;
        }
    }

    public static JsValue getMethod(JsDate receiver, String name, InterpreterOps ops) {
        final var method = instanceMethod(receiver, name, ops);
        if (method != null) {
            return method;
        }
        return getter(receiver, name);
    }

    private static JsValue instanceMethod(JsDate receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "getTime", "valueOf" -> new JsNativeFunction(name, (_, _) -> new JsNumber(receiver.getTime()));
            case "setTime" -> new JsNativeFunction("setTime", (_, args) -> {
                receiver.setTime(timeClip(args.isEmpty() ? Double.NaN : JsCoercion.toNumber(args.getFirst(), ops)));
                return new JsNumber(receiver.getTime());
            });
            case "toISOString" -> new JsNativeFunction("toISOString", (_, _) -> toISOString(receiver));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> toJSON(receiver));
            case "toString", "toDateString", "toUTCString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(receiver.toDateString()));
            case "toLocaleString", "toLocaleDateString", "toLocaleTimeString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(toLocaleString(receiver, name)));
            case "getTimezoneOffset" -> new JsNativeFunction("getTimezoneOffset", (_, _) -> new JsNumber(0));
            default -> setter(receiver, name, ops);
        };
    }

    private static JsValue setter(JsDate receiver, String name, InterpreterOps ops) {
        final var arity = setterArity(name);
        if (arity == 0) {
            return null;
        }
        return new JsNativeFunction(name, (_, args) -> {
            // thisTimeValue is read before any argument coercion, and every argument is coerced
            // (left to right) even when the receiver is already an invalid date.
            final var start = receiver.getTime();
            final var provided = Math.min(args.size(), arity);
            final var values = new double[arity];
            for (var i = 0; i < provided; i++) {
                values[i] = JsCoercion.toNumber(args.get(i), ops);
            }
            final var isYear = name.endsWith("FullYear");
            if (Double.isNaN(start) && !isYear) {
                return new JsNumber(Double.NaN);
            }
            final var base = Double.isNaN(start) ? 0 : start;
            receiver.setTime(timeClip(recompose(name, base, values, provided)));
            return new JsNumber(receiver.getTime());
        });
    }

    private static int setterArity(String name) {
        return switch (name) {
            case "setFullYear", "setUTCFullYear", "setMinutes", "setUTCMinutes" -> 3;
            case "setMonth", "setUTCMonth", "setSeconds", "setUTCSeconds" -> 2;
            case "setDate", "setUTCDate", "setMilliseconds", "setUTCMilliseconds" -> 1;
            case "setHours", "setUTCHours" -> 4;
            default -> 0;
        };
    }

    private static double recompose(String name, double t, double[] values, int provided) {
        return switch (name) {
            case "setFullYear", "setUTCFullYear" ->
                makeDate(makeDay(values[0], provided > 1 ? values[1] : monthFromTime(t),
                        provided > 2 ? values[2] : dateFromTime(t)), timeWithinDay(t));
            case "setMonth", "setUTCMonth" -> makeDate(
                    makeDay(yearFromTime(t), values[0], provided > 1 ? values[1] : dateFromTime(t)), timeWithinDay(t));
            case "setDate", "setUTCDate" ->
                makeDate(makeDay(yearFromTime(t), monthFromTime(t), values[0]), timeWithinDay(t));
            case "setHours", "setUTCHours" ->
                makeDate(day(t), makeTime(values[0], provided > 1 ? values[1] : minFromTime(t),
                        provided > 2 ? values[2] : secFromTime(t), provided > 3 ? values[3] : msFromTime(t)));
            case "setMinutes", "setUTCMinutes" -> makeDate(day(t), makeTime(hourFromTime(t), values[0],
                    provided > 1 ? values[1] : secFromTime(t), provided > 2 ? values[2] : msFromTime(t)));
            case "setSeconds", "setUTCSeconds" -> makeDate(day(t),
                    makeTime(hourFromTime(t), minFromTime(t), values[0], provided > 1 ? values[1] : msFromTime(t)));
            default -> makeDate(day(t), makeTime(hourFromTime(t), minFromTime(t), secFromTime(t), values[0]));
        };
    }

    // The spec's date arithmetic is unbounded: an out-of-range component rolls over rather than
    // throwing, and only TimeClip at the end collapses an impossible instant to NaN.
    private static double makeDay(double year, double month, double date) {
        if (!Double.isFinite(year) || !Double.isFinite(month) || !Double.isFinite(date)) {
            return Double.NaN;
        }
        final var y = truncate(year);
        final var m = truncate(month);
        final var ym = y + Math.floor(m / 12);
        if (Math.abs(ym) > MAX_YEAR) {
            return Double.NaN;
        }
        final var mn = (int) (m - Math.floor(m / 12) * 12);
        return java.time.LocalDate.of((int) ym, mn + 1, 1).toEpochDay() + truncate(date) - 1;
    }

    private static double makeTime(double hour, double minute, double second, double millis) {
        if (!Double.isFinite(hour) || !Double.isFinite(minute) || !Double.isFinite(second)
                || !Double.isFinite(millis)) {
            return Double.NaN;
        }
        return truncate(hour) * MS_PER_HOUR + truncate(minute) * MS_PER_MINUTE + truncate(second) * MS_PER_SECOND
                + truncate(millis);
    }

    private static double makeDate(double day, double time) {
        if (!Double.isFinite(day) || !Double.isFinite(time)) {
            return Double.NaN;
        }
        final var result = day * MS_PER_DAY + time;
        return Double.isFinite(result) ? result : Double.NaN;
    }

    private static double timeClip(double time) {
        if (!Double.isFinite(time) || Math.abs(time) > MAX_TIME) {
            return Double.NaN;
        }
        return truncate(time) + 0d;
    }

    private static double truncate(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    private static double day(double t) {
        return Math.floor(t / MS_PER_DAY);
    }

    private static double timeWithinDay(double t) {
        return t - day(t) * MS_PER_DAY;
    }

    private static double hourFromTime(double t) {
        return floorMod(Math.floor(t / MS_PER_HOUR), 24);
    }

    private static double minFromTime(double t) {
        return floorMod(Math.floor(t / MS_PER_MINUTE), 60);
    }

    private static double secFromTime(double t) {
        return floorMod(Math.floor(t / MS_PER_SECOND), 60);
    }

    private static double msFromTime(double t) {
        return floorMod(t, MS_PER_SECOND);
    }

    private static double yearFromTime(double t) {
        return java.time.LocalDate.ofEpochDay((long) day(t)).getYear();
    }

    private static double monthFromTime(double t) {
        return java.time.LocalDate.ofEpochDay((long) day(t)).getMonthValue() - 1d;
    }

    private static double dateFromTime(double t) {
        return java.time.LocalDate.ofEpochDay((long) day(t)).getDayOfMonth();
    }

    private static double floorMod(double value, double modulus) {
        return value - Math.floor(value / modulus) * modulus;
    }

    private static JsValue getter(JsDate receiver, String name) {
        final var component = componentName(name);
        if (component == null) {
            return null;
        }
        return new JsNativeFunction(name, (_, _) -> {
            if (!receiver.isValid()) {
                return new JsNumber(Double.NaN);
            }
            final var zoned = receiver.atUtc();
            final int value = switch (component) {
                case "year" -> zoned.getYear();
                case "month" -> zoned.getMonthValue() - 1;
                case "date" -> zoned.getDayOfMonth();
                case "day" -> zoned.getDayOfWeek().getValue() % 7;
                case "hours" -> zoned.getHour();
                case "minutes" -> zoned.getMinute();
                case "seconds" -> zoned.getSecond();
                default -> zoned.getNano() / 1_000_000;
            };
            return new JsNumber(value);
        });
    }

    private static String componentName(String name) {
        return switch (name) {
            case "getFullYear", "getUTCFullYear" -> "year";
            case "getMonth", "getUTCMonth" -> "month";
            case "getDate", "getUTCDate" -> "date";
            case "getDay", "getUTCDay" -> "day";
            case "getHours", "getUTCHours" -> "hours";
            case "getMinutes", "getUTCMinutes" -> "minutes";
            case "getSeconds", "getUTCSeconds" -> "seconds";
            case "getMilliseconds", "getUTCMilliseconds" -> "millis";
            default -> null;
        };
    }

    private static JsValue toISOString(JsDate receiver) {
        final var iso = receiver.toISOString();
        if (iso == null) {
            throw new RangeErrorException("Invalid time value");
        }
        return new JsString(iso);
    }

    private static String toLocaleString(JsDate receiver, String name) {
        if (!receiver.isValid()) {
            return "Invalid Date";
        }
        final var style = java.time.format.FormatStyle.MEDIUM;
        final var formatter = switch (name) {
            case "toLocaleDateString" -> java.time.format.DateTimeFormatter.ofLocalizedDate(style);
            case "toLocaleTimeString" -> java.time.format.DateTimeFormatter.ofLocalizedTime(style);
            default -> java.time.format.DateTimeFormatter.ofLocalizedDateTime(style);
        };
        final var zoned = Instant.ofEpochMilli((long) receiver.getTime()).atZone(ZoneOffset.UTC);
        return zoned.format(formatter.withLocale(java.util.Locale.getDefault()));
    }

    private static JsValue toJSON(JsDate receiver) {
        final var iso = receiver.toISOString();
        return iso == null ? JsNull.getInstance() : new JsString(iso);
    }

    private static double doubleArg(List<JsValue> args, int position, double fallback) {
        return position < args.size() ? JsCoercion.toNumber(args.get(position)) : fallback;
    }

    // Date.prototype[Symbol.toPrimitive] runs OrdinaryToPrimitive with a hint-derived order, so a
    // "default" hint prefers toString (unlike every other object, where it prefers valueOf).
    public static JsNativeFunction symbolToPrimitive(InterpreterOps ops) {
        final var method = new JsNativeFunction("[Symbol.toPrimitive]", (thisArg, args) -> {
            if (!InterpreterUtils.isObjectLike(thisArg)) {
                throw new TypeErrorException("Date.prototype[Symbol.toPrimitive] called on a non-object");
            }
            final var hint = args.isEmpty() ? "" : hintOf(args.getFirst());
            if ("string".equals(hint) || "default".equals(hint)) {
                return ordinaryToPrimitive(thisArg, ops, "toString", "valueOf");
            }
            if ("number".equals(hint)) {
                return ordinaryToPrimitive(thisArg, ops, "valueOf", "toString");
            }
            throw new TypeErrorException("Invalid hint passed to Date.prototype[Symbol.toPrimitive]");
        });
        method.setLength(1);
        return method;
    }

    private static String hintOf(JsValue hint) {
        return hint instanceof JsString string ? string.getValue() : "";
    }

    private static JsValue ordinaryToPrimitive(JsValue target, InterpreterOps ops, String... order) {
        if (ops == null) {
            return new JsString(JsCoercion.toStr(target));
        }
        for (final var name : order) {
            final var method = ops.getMember(target, new JsString(name));
            if (!InterpreterUtils.isCallable(method)) {
                continue;
            }
            final var result = ops.call(method, target, List.of());
            if (!InterpreterUtils.isObjectLike(result)) {
                return result;
            }
        }
        throw new TypeErrorException("Cannot convert a Date to a primitive value");
    }

    private static String str(List<JsValue> args) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
    }
}
