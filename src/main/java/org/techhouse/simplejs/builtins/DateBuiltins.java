package org.techhouse.simplejs.builtins;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
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
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DateBuiltins {
    public static final List<String> NAMES = List.of("getTime", "valueOf", "setTime", "toISOString", "toJSON",
            "toString", "toDateString", "toTimeString", "toUTCString", "toLocaleString", "toLocaleDateString",
            "toLocaleTimeString", "getTimezoneOffset", "getFullYear", "getUTCFullYear", "getMonth", "getUTCMonth",
            "getDate", "getUTCDate", "getDay", "getUTCDay", "getHours", "getUTCHours", "getMinutes", "getUTCMinutes",
            "getSeconds", "getUTCSeconds", "getMilliseconds", "getUTCMilliseconds", "setFullYear", "setUTCFullYear",
            "setMonth", "setUTCMonth", "setDate", "setUTCDate", "setHours", "setUTCHours", "setMinutes",
            "setUTCMinutes", "setSeconds", "setUTCSeconds", "setMilliseconds", "setUTCMilliseconds");

    private static final double MS_PER_SECOND = 1000;
    private static final double MS_PER_MINUTE = 60_000;
    private static final double MS_PER_HOUR = 3_600_000;
    private static final double MS_PER_DAY = 86_400_000;
    private static final double MAX_TIME = 8.64e15;
    private static final double MAX_YEAR = 400_000;
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
        // Called as a plain function Date() answers a string. A `class X extends Date` super-call
        // reaches the same lambda with no new.target but *with* the instance as its receiver, which
        // is what separates the two here.
        final var date = new JsNativeFunction("Date",
                (thisArg,
                        args) -> JsNativeFunction.currentNewTarget() == null && !InterpreterUtils.isObjectLike(thisArg)
                                ? new JsString(toDateString(System.currentTimeMillis()))
                                : withNewTargetPrototype(new JsDate(construct(args, ops)), ops));
        date.setProperty("now", new JsNativeFunction("now", (_, _) -> new JsNumber(System.currentTimeMillis())));
        date.setProperty("parse", new JsNativeFunction("parse", (_, args) -> new JsNumber(parse(str(args, ops)))));
        date.setProperty("UTC",
                new JsNativeFunction("UTC", (_, args) -> new JsNumber(timeClip(fromComponents(args, ops)))));
        return date;
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(Date, args, Ctor) must link the new instance's
    // [[Prototype]] to Ctor.prototype rather than always to the intrinsic Date.prototype (mirrors the
    // same idiom TypedArrayBuiltins/JsArrayBuffer use for their own constructors). A `class X extends
    // Date` super-call reaches this constructor with no new.target (see the branch above), so this
    // path is exercised only by a direct/reflective `new`.
    private static JsValue withNewTargetPrototype(JsDate constructed, InterpreterOps ops) {
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
                    primitive instanceof JsString s ? parse(s.getValue()) : JsCoercion.toNumber(primitive, ops));
        }
        return timeClip(utcFromLocal(fromComponents(args, ops)));
    }

    private static double fromComponents(List<JsValue> args, InterpreterOps ops) {
        var year = doubleArg(args, 0, Double.NaN, ops);
        // The 1900 offset keys off the *truncated* year, so -0.999999 still maps to 1900.
        final var truncatedYear = truncate(year);
        if (!Double.isNaN(year) && truncatedYear >= 0 && truncatedYear <= 99) {
            year = 1900 + truncatedYear;
        }
        final var day = makeDay(year, doubleArg(args, 1, 0, ops), doubleArg(args, 2, 1, ops));
        final var time = makeTime(doubleArg(args, 3, 0, ops), doubleArg(args, 4, 0, ops), doubleArg(args, 5, 0, ops),
                doubleArg(args, 6, 0, ops));
        return makeDate(day, time);
    }

    private static double parse(String value) {
        final var trimmed = JsCoercion.stripJs(value);
        final var iso = parseIso(trimmed);
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

    // The Date Time String Format: a date-only form is UTC, a date-time form without an offset is
    // local time, and the year may carry the extended six-digit ±YYYYYY spelling.
    private static double parseIso(String text) {
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
            return matcher.group(4) == null ? timeClip(value) : timeClip(utcFromLocal(value));
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

    // Date.prototype.toJSON carries no [[DateValue]] brand check: it is specified over ToObject(this)
    // plus a ToPrimitive, so it has to work on an arbitrary receiver.
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
            case "toString" -> new JsNativeFunction(name, (_, _) -> new JsString(toDateString(receiver.getTime())));
            case "toDateString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(localPart(receiver.getTime(), true, false)));
            case "toTimeString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(localPart(receiver.getTime(), false, true)));
            case "toUTCString" -> new JsNativeFunction(name, (_, _) -> new JsString(utcString(receiver.getTime())));
            case "toLocaleString", "toLocaleDateString", "toLocaleTimeString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(toLocaleString(receiver, name)));
            case "getTimezoneOffset" -> new JsNativeFunction("getTimezoneOffset", (_, _) -> new JsNumber(
                    // 0.0 - x (not unary -x) so a zero offset stays +0, matching SameValue expectations.
                    receiver.isValid() ? (0.0 - localOffset(receiver.getTime())) / MS_PER_MINUTE : Double.NaN));
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
            // thisTimeValue is read before any argument coercion, and every argument is coerced
            // (left to right) even when the receiver is already an invalid date.
            final var start = receiver.getTime();
            // The leading argument is always ToNumber'd, so setMilliseconds() with no argument sees
            // NaN rather than keeping the current component; the trailing ones only when present.
            final var provided = Math.clamp(args.size(), 1, arity);
            final var values = new double[arity];
            for (var i = 0; i < provided; i++) {
                values[i] = JsCoercion.toNumber(i < args.size() ? args.get(i) : JsUndefined.getInstance(), ops);
            }
            final var isYear = name.endsWith("FullYear");
            if (Double.isNaN(start) && !isYear) {
                return new JsNumber(Double.NaN);
            }
            final var base = Double.isNaN(start) ? 0 : utc ? start : localTime(start);
            final var recomposed = recompose(name, base, values, provided);
            receiver.setTime(timeClip(utc ? recomposed : utcFromLocal(recomposed)));
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

    private static ZoneRules zoneRules() {
        return ZoneId.systemDefault().getRules();
    }

    // LocalTZA at a UTC instant. Kept out of JsDate so the value type stays a bare epoch-millis
    // carrier and EJsonInterop's ISO mapping is unaffected by the local-time work.
    private static double localOffset(double utcTime) {
        if (!Double.isFinite(utcTime) || Math.abs(utcTime) > MAX_TIME) {
            return 0;
        }
        return zoneRules().getOffset(Instant.ofEpochMilli((long) utcTime)).getTotalSeconds() * MS_PER_SECOND;
    }

    private static double localTime(double utcTime) {
        return Double.isNaN(utcTime) ? utcTime : utcTime + localOffset(utcTime);
    }

    // The inverse of LocalTime: a local wall-clock reading back to a UTC instant, preferring the
    // earlier offset when the reading is ambiguous (a fall-back transition) as the spec does.
    private static double utcFromLocal(double localTimeValue) {
        if (!Double.isFinite(localTimeValue) || Math.abs(localTimeValue) > MAX_TIME + MS_PER_DAY) {
            return localTimeValue;
        }
        final var millis = (long) localTimeValue;
        final var local = LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000L),
                (int) (Math.floorMod(millis, 1000L) * 1_000_000L), ZoneOffset.UTC);
        final var rules = zoneRules();
        final var offsets = rules.getValidOffsets(local);
        final var offset = offsets.isEmpty() ? rules.getOffset(local) : offsets.getFirst();
        return localTimeValue - offset.getTotalSeconds() * MS_PER_SECOND;
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

    private static double weekDay(double t) {
        return floorMod(day(t) + 4, 7);
    }

    private static double floorMod(double value, double modulus) {
        return value - Math.floor(value / modulus) * modulus;
    }

    private static JsValue getter(JsDate receiver, String name) {
        final var component = componentName(name);
        if (component == null) {
            return null;
        }
        final var utc = name.startsWith("getUTC");
        return new JsNativeFunction(name, (_, _) -> {
            if (!receiver.isValid()) {
                return new JsNumber(Double.NaN);
            }
            final var t = utc ? receiver.getTime() : localTime(receiver.getTime());
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

    private static String timeZoneStringOf(double utcTime) {
        final var offset = (long) (localOffset(utcTime) / MS_PER_MINUTE);
        final var sign = offset < 0 ? "-" : "+";
        final var magnitude = Math.abs(offset);
        return sign + String.format(Locale.US, "%02d%02d", magnitude / 60, magnitude % 60) + " ("
                + ZoneId.systemDefault().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + ")";
    }

    private static String toDateString(double utcTime) {
        if (Double.isNaN(utcTime)) {
            return INVALID;
        }
        final var t = localTime(utcTime);
        return dateStringOf(t) + " " + timeStringOf(t) + timeZoneStringOf(utcTime);
    }

    private static String localPart(double utcTime, boolean datePart, boolean timePart) {
        if (Double.isNaN(utcTime)) {
            return INVALID;
        }
        final var t = localTime(utcTime);
        if (datePart) {
            return dateStringOf(t);
        }
        return timePart ? timeStringOf(t) + timeZoneStringOf(utcTime) : "";
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

    // The extended-year spelling is required whenever the year escapes 0000-9999, which is exactly
    // the range Date.parse has to be able to read back.
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

    private static String toLocaleString(JsDate receiver, String name) {
        if (!receiver.isValid()) {
            return INVALID;
        }
        final var style = java.time.format.FormatStyle.MEDIUM;
        final var formatter = switch (name) {
            case "toLocaleDateString" -> java.time.format.DateTimeFormatter.ofLocalizedDate(style);
            case "toLocaleTimeString" -> java.time.format.DateTimeFormatter.ofLocalizedTime(style);
            default -> java.time.format.DateTimeFormatter.ofLocalizedDateTime(style);
        };
        final var zoned = Instant.ofEpochMilli((long) receiver.getTime()).atZone(ZoneId.systemDefault());
        return zoned.format(formatter.withLocale(java.util.Locale.getDefault()));
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
