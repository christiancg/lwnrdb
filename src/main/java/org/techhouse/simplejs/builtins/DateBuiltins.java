package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.dateFromTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.day;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.hourFromTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.localOffset;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.localTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.makeDate;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.makeDay;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.makeTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.minFromTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.monthFromTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.msFromTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.secFromTime;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.timeClip;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.timeWithinDay;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.truncate;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.utcFromLocal;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.weekDay;
import static org.techhouse.simplejs.builtins.date.DateArithmetic.yearFromTime;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.MS_PER_MINUTE;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DateBuiltins {
    public static final List<String> NAMES = List.of("getTime", "valueOf", "setTime", "toISOString", "toJSON",
            "toString", "toDateString", "toTimeString", "toUTCString", "toLocaleString", "toLocaleDateString",
            "toLocaleTimeString", "getTimezoneOffset", "getFullYear", "getUTCFullYear", "getMonth", "getUTCMonth",
            "getDate", "getUTCDate", "getDay", "getUTCDay", "getHours", "getUTCHours", "getMinutes", "getUTCMinutes",
            "getSeconds", "getUTCSeconds", "getMilliseconds", "getUTCMilliseconds", "setFullYear", "setUTCFullYear",
            "setMonth", "setUTCMonth", "setDate", "setUTCDate", "setHours", "setUTCHours", "setMinutes",
            "setUTCMinutes", "setSeconds", "setUTCSeconds", "setMilliseconds", "setUTCMilliseconds",
            "toTemporalInstant");

    public static final double MAX_TIME = 8.64e15;
    public static final double MAX_YEAR = 400_000;
    private static final String INVALID = "Invalid Date";
    private static final String[] WEEKDAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov",
            "Dec"};
    private static final Pattern ISO = Pattern.compile("([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?"
            + "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,3}))?)?(Z|[+-]\\d{2}:?\\d{2})?)?");
    private static final Pattern UTC_STRING = Pattern
            .compile("\\w{3}, (\\d{2}) (\\w{3}) (-?\\d{4,6}) (\\d{2}):(\\d{2}):(\\d{2}) GMT");
    private static final Pattern LOCAL_STRING = Pattern
            .compile("\\w{3} (\\w{3}) (\\d{2}) (-?\\d{4,6}) (\\d{2}):(\\d{2}):(\\d{2}) GMT([+-]\\d{4})(?: \\(.*\\))?");

    private DateBuiltins() {
    }

    public static JsNativeFunction create() {
        return create(null);
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var date = new JsNativeFunction("Date",
                (thisArg,
                        args) -> JsNativeFunction.currentNewTarget() == null && !InterpreterUtils.isObjectLike(thisArg)
                                ? new JsString(toDateString(System.currentTimeMillis(), ops))
                                : withNewTargetPrototype(new JsDate(construct(args, ops)), ops));
        date.setProperty("now", new JsNativeFunction("now", (_, _) -> new JsNumber(System.currentTimeMillis())));
        date.setProperty("parse", new JsNativeFunction("parse", (_, args) -> new JsNumber(parse(str(args, ops), ops))));
        date.setProperty("UTC",
                new JsNativeFunction("UTC", (_, args) -> new JsNumber(timeClip(fromComponents(args, ops)))));
        return date;
    }

    private static double construct(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty()) {
            return System.currentTimeMillis();
        }
        if (args.size() == 1) {
            final var arg = args.getFirst();
            if (arg instanceof JsDate date) {
                return timeClip(date.getTime());
            }
            final var primitive = JsCoercion.toPrimitive(arg, "default", ops);
            return timeClip(
                    primitive instanceof JsString s ? parse(s.getValue(), ops) : JsCoercion.toNumber(primitive, ops));
        }
        return timeClip(utcFromLocal(fromComponents(args, ops), ops));
    }

    private static double fromComponents(List<JsValue> args, InterpreterOps ops) {
        var year = doubleArg(args, 0, Double.NaN, ops);
        final var truncatedYear = truncate(year);
        if (!Double.isNaN(year) && truncatedYear >= 0 && truncatedYear <= 99) {
            year = 1900 + truncatedYear;
        }
        final var day = makeDay(year, doubleArg(args, 1, 0, ops), doubleArg(args, 2, 1, ops));
        final var time = makeTime(doubleArg(args, 3, 0, ops), doubleArg(args, 4, 0, ops), doubleArg(args, 5, 0, ops),
                doubleArg(args, 6, 0, ops));
        return makeDate(day, time);
    }

    private static double parse(String value, InterpreterOps ops) {
        final var trimmed = JsCoercion.stripJs(value);
        final var iso = parseIso(trimmed, ops);
        if (!Double.isNaN(iso)) {
            return iso;
        }
        final var utc = UTC_STRING.matcher(trimmed);
        if (utc.matches()) {
            return fromMatch(monthIndex(utc.group(2)), utc.group(3), utc.group(1), utc.group(4), utc.group(5),
                    utc.group(6), 0);
        }
        final var local = LOCAL_STRING.matcher(trimmed);
        if (local.matches()) {
            final var raw = local.group(7);
            final var offset = (Integer.parseInt(raw.substring(1, 3)) * 60 + Integer.parseInt(raw.substring(3)))
                    * MS_PER_MINUTE * (raw.charAt(0) == '-' ? -1 : 1);
            return fromMatch(monthIndex(local.group(1)), local.group(3), local.group(2), local.group(4), local.group(5),
                    local.group(6), offset);
        }
        return Double.NaN;
    }

    private static double fromMatch(int month, String year, String day, String hour, String minute, String second,
            double offsetMillis) {
        if (month < 0) {
            return Double.NaN;
        }
        final var date = makeDay(Double.parseDouble(year), month, Double.parseDouble(day));
        final var time = makeTime(Double.parseDouble(hour), Double.parseDouble(minute), Double.parseDouble(second), 0);
        return timeClip(makeDate(date, time) - offsetMillis);
    }

    private static int monthIndex(String name) {
        for (var i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static double parseIso(String text, InterpreterOps ops) {
        final var matcher = ISO.matcher(text);
        if (!matcher.matches()) {
            return Double.NaN;
        }
        final var year = Double
                .parseDouble(matcher.group(1).startsWith("+") ? matcher.group(1).substring(1) : matcher.group(1));
        if ("-000000".equals(matcher.group(1))) {
            return Double.NaN;
        }
        final var month = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)) - 1;
        final var day = matcher.group(3) == null ? 1 : Integer.parseInt(matcher.group(3));
        final var hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        final var minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
        final var second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
        final var millis = matcher.group(7) == null ? 0 : Integer.parseInt((matcher.group(7) + "00").substring(0, 3));
        if (month > 11 || day < 1 || day > 31 || minute > 59 || second > 59 || hour > 24
                || (hour == 24 && (minute | second | millis) != 0)) {
            return Double.NaN;
        }
        final var value = makeDate(makeDay(year, month, day), makeTime(hour, minute, second, millis));
        final var zone = matcher.group(8);
        if (zone == null) {
            return matcher.group(4) == null ? timeClip(value) : timeClip(utcFromLocal(value, ops));
        }
        if ("Z".equals(zone)) {
            return timeClip(value);
        }
        final var digits = zone.replace(":", "");
        final var offset = (Integer.parseInt(digits.substring(1, 3)) * 60 + Integer.parseInt(digits.substring(3)))
                * MS_PER_MINUTE * (digits.charAt(0) == '-' ? -1 : 1);
        return timeClip(value - offset);
    }

    public static boolean isGeneric(String name) {
        return "toJSON".equals(name);
    }

    public static JsValue genericMethod(String name, InterpreterOps ops) {
        if (!isGeneric(name)) {
            return null;
        }
        return new JsNativeFunction("toJSON", (thisArg, _) -> toJSON(thisArg, ops));
    }

    public static JsValue getMethod(JsDate receiver, String name, InterpreterOps ops) {
        if (isGeneric(name)) {
            return genericMethod(name, ops);
        }
        final var method = instanceMethod(receiver, name, ops);
        if (method != null) {
            return method;
        }
        return getter(receiver, name, ops);
    }

    private static JsValue instanceMethod(JsDate receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "getTime", "valueOf" -> new JsNativeFunction(name, (_, _) -> new JsNumber(receiver.getTime()));
            case "setTime" -> new JsNativeFunction("setTime", (_, args) -> {
                receiver.setTime(timeClip(args.isEmpty() ? Double.NaN : JsCoercion.toNumber(args.getFirst(), ops)));
                return new JsNumber(receiver.getTime());
            });
            case "toISOString" -> new JsNativeFunction("toISOString", (_, _) -> toISOString(receiver));
            case "toString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(toDateString(receiver.getTime(), ops)));
            case "toDateString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(localPart(receiver.getTime(), true, false, ops)));
            case "toTimeString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(localPart(receiver.getTime(), false, true, ops)));
            case "toUTCString" -> new JsNativeFunction(name, (_, _) -> new JsString(utcString(receiver.getTime())));
            case "toLocaleString", "toLocaleDateString", "toLocaleTimeString" ->
                new JsNativeFunction(name, (_, args) -> new JsString(toLocaleString(receiver, name, args, ops)));
            case "getTimezoneOffset" -> new JsNativeFunction("getTimezoneOffset", (_, _) -> new JsNumber(
                    receiver.isValid() ? (0.0 - localOffset(receiver.getTime(), ops)) / MS_PER_MINUTE : Double.NaN));
            case "toTemporalInstant" ->
                new JsNativeFunction(name, (_, _) -> JsTemporalInstant.fromEpochMilliseconds(receiver.getTime()));
            default -> setter(receiver, name, ops);
        };
    }

    private static JsValue setter(JsDate receiver, String name, InterpreterOps ops) {
        final var arity = setterArity(name);
        if (arity == 0) {
            return null;
        }
        final var utc = name.startsWith("setUTC");
        return new JsNativeFunction(name, (_, args) -> {
            final var start = receiver.getTime();
            final var provided = Math.clamp(args.size(), 1, arity);
            final var values = new double[arity];
            for (var i = 0; i < provided; i++) {
                values[i] = JsCoercion.toNumber(i < args.size() ? args.get(i) : JsUndefined.getInstance(), ops);
            }
            final var isYear = name.endsWith("FullYear");
            if (Double.isNaN(start) && !isYear) {
                return new JsNumber(Double.NaN);
            }
            final var base = Double.isNaN(start) ? 0 : utc ? start : localTime(start, ops);
            final var recomposed = recompose(name, base, values, provided);
            receiver.setTime(timeClip(utc ? recomposed : utcFromLocal(recomposed, ops)));
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

    private static JsValue getter(JsDate receiver, String name, InterpreterOps ops) {
        final var component = componentName(name);
        if (component == null) {
            return null;
        }
        final var utc = name.startsWith("getUTC");
        return new JsNativeFunction(name, (_, _) -> {
            if (!receiver.isValid()) {
                return new JsNumber(Double.NaN);
            }
            final var t = utc ? receiver.getTime() : localTime(receiver.getTime(), ops);
            final double value = switch (component) {
                case "year" -> yearFromTime(t);
                case "month" -> monthFromTime(t);
                case "date" -> dateFromTime(t);
                case "day" -> weekDay(t);
                case "hours" -> hourFromTime(t);
                case "minutes" -> minFromTime(t);
                case "seconds" -> secFromTime(t);
                default -> msFromTime(t);
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

    private static String paddedYear(double year) {
        final var absolute = String.format(Locale.US, "%04d", (long) Math.abs(year));
        return year < 0 ? "-" + absolute : absolute;
    }

    private static String dateStringOf(double t) {
        return WEEKDAYS[(int) weekDay(t)] + " " + MONTHS[(int) monthFromTime(t)] + " "
                + String.format(Locale.US, "%02d", (long) dateFromTime(t)) + " " + paddedYear(yearFromTime(t));
    }

    private static String timeStringOf(double t) {
        return String.format(Locale.US, "%02d:%02d:%02d GMT", (long) hourFromTime(t), (long) minFromTime(t),
                (long) secFromTime(t));
    }

    private static String timeZoneStringOf(double utcTime, InterpreterOps ops) {
        final var offset = (long) (localOffset(utcTime, ops) / MS_PER_MINUTE);
        final var sign = offset < 0 ? "-" : "+";
        final var magnitude = Math.abs(offset);
        return sign + String.format(Locale.US, "%02d%02d", magnitude / 60, magnitude % 60) + " ("
                + InterpreterOps.timeZone(ops).getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + ")";
    }

    private static String toDateString(double utcTime, InterpreterOps ops) {
        if (Double.isNaN(utcTime)) {
            return INVALID;
        }
        final var t = localTime(utcTime, ops);
        return dateStringOf(t) + " " + timeStringOf(t) + timeZoneStringOf(utcTime, ops);
    }

    private static String localPart(double utcTime, boolean datePart, boolean timePart, InterpreterOps ops) {
        if (Double.isNaN(utcTime)) {
            return INVALID;
        }
        final var t = localTime(utcTime, ops);
        if (datePart) {
            return dateStringOf(t);
        }
        return timePart ? timeStringOf(t) + timeZoneStringOf(utcTime, ops) : "";
    }

    private static String utcString(double utcTime) {
        if (Double.isNaN(utcTime)) {
            return INVALID;
        }
        return WEEKDAYS[(int) weekDay(utcTime)] + ", " + String.format(Locale.US, "%02d", (long) dateFromTime(utcTime))
                + " " + MONTHS[(int) monthFromTime(utcTime)] + " " + paddedYear(yearFromTime(utcTime)) + " "
                + timeStringOf(utcTime);
    }

    private static JsValue toISOString(JsDate receiver) {
        if (!receiver.isValid()) {
            throw new RangeErrorException("Invalid time value");
        }
        return new JsString(isoString(receiver.getTime()));
    }

    private static String isoString(double t) {
        final var year = (long) yearFromTime(t);
        final String yearText;
        if (year < 0) {
            yearText = "-" + String.format(Locale.US, "%06d", -year);
        } else if (year > 9999) {
            yearText = "+" + String.format(Locale.US, "%06d", year);
        } else {
            yearText = String.format(Locale.US, "%04d", year);
        }
        return yearText + String.format(Locale.US, "-%02d-%02dT%02d:%02d:%02d.%03dZ", (long) monthFromTime(t) + 1,
                (long) dateFromTime(t), (long) hourFromTime(t), (long) minFromTime(t), (long) secFromTime(t),
                (long) msFromTime(t));
    }

    private static String toLocaleString(JsDate receiver, String name, List<JsValue> args, InterpreterOps ops) {
        if (!receiver.isValid()) {
            return INVALID;
        }
        final var style = java.time.format.FormatStyle.MEDIUM;
        final var formatter = switch (name) {
            case "toLocaleDateString" -> java.time.format.DateTimeFormatter.ofLocalizedDate(style);
            case "toLocaleTimeString" -> java.time.format.DateTimeFormatter.ofLocalizedTime(style);
            default -> java.time.format.DateTimeFormatter.ofLocalizedDateTime(style);
        };
        final var zoned = Instant.ofEpochMilli((long) receiver.getTime()).atZone(InterpreterOps.timeZone(ops));
        return zoned.format(formatter.withLocale(LocaleResolver.resolve(args, 0, ops)));
    }

    private static JsValue toJSON(JsValue receiver, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(receiver) && ops != null) {
            return toJSONPrimitive(receiver, ops);
        }
        if (ops == null) {
            return receiver instanceof JsDate date && date.isValid()
                    ? new JsString(isoString(date.getTime()))
                    : JsNull.getInstance();
        }
        final var primitive = JsCoercion.toPrimitive(receiver, "number", ops);
        if (primitive instanceof JsNumber number && !Double.isFinite(number.getValue())) {
            return JsNull.getInstance();
        }
        return invokeToISOString(receiver, ops);
    }

    private static JsValue toJSONPrimitive(JsValue receiver, InterpreterOps ops) {
        if (receiver instanceof JsNull || receiver instanceof JsUndefined) {
            throw new TypeErrorException("Date.prototype.toJSON called on null or undefined");
        }
        return invokeToISOString(receiver, ops);
    }

    private static JsValue invokeToISOString(JsValue receiver, InterpreterOps ops) {
        final var method = ops.getMember(receiver, new JsString("toISOString"));
        if (!InterpreterUtils.isCallable(method)) {
            throw new TypeErrorException("toISOString is not a function");
        }
        return ops.call(method, receiver, List.of());
    }

    private static double doubleArg(List<JsValue> args, int position, double fallback, InterpreterOps ops) {
        return position < args.size() ? JsCoercion.toNumber(args.get(position), ops) : fallback;
    }

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
            return new JsString(JsCoercion.toStrDataOnly(target));
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

    private static String str(List<JsValue> args, InterpreterOps ops) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), ops);
    }
}
