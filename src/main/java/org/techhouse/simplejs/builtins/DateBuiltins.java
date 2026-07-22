package org.techhouse.simplejs.builtins;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DateBuiltins {
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
            return switch (arg) {
                case JsDate date -> date.getTime();
                case JsString s -> parse(s.getValue());
                default -> JsCoercion.toNumber(arg);
            };
        }
        return fromComponents(args);
    }

    private static double fromComponents(List<JsValue> args) {
        try {
            var year = intArg(args, 0, 1970);
            if (year >= 0 && year <= 99) {
                year += 1900;
            }
            final var month = intArg(args, 1, 0);
            final var day = intArg(args, 2, 1);
            final var hours = intArg(args, 3, 0);
            final var minutes = intArg(args, 4, 0);
            final var seconds = intArg(args, 5, 0);
            final var millis = intArg(args, 6, 0);
            final var base = LocalDateTime.of(year, 1, 1, 0, 0, 0).plusMonths(month).plusDays(day - 1L).plusHours(hours)
                    .plusMinutes(minutes).plusSeconds(seconds);
            return base.toInstant(ZoneOffset.UTC).toEpochMilli() + millis;
        } catch (DateTimeException | ArithmeticException ignored) {
            return Double.NaN;
        }
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

    public static JsValue getMethod(JsDate receiver, String name) {
        final var method = instanceMethod(receiver, name);
        if (method != null) {
            return method;
        }
        return getter(receiver, name);
    }

    private static JsValue instanceMethod(JsDate receiver, String name) {
        return switch (name) {
            case "getTime", "valueOf" -> new JsNativeFunction(name, (_, _) -> new JsNumber(receiver.getTime()));
            case "setTime" -> new JsNativeFunction("setTime", (_, args) -> {
                receiver.setTime(args.isEmpty() ? Double.NaN : JsCoercion.toNumber(args.getFirst()));
                return new JsNumber(receiver.getTime());
            });
            case "toISOString" -> new JsNativeFunction("toISOString", (_, _) -> toISOString(receiver));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> toJSON(receiver));
            case "toString", "toDateString", "toUTCString" ->
                new JsNativeFunction(name, (_, _) -> new JsString(receiver.toDateString()));
            case "getTimezoneOffset" -> new JsNativeFunction("getTimezoneOffset", (_, _) -> new JsNumber(0));
            default -> setter(receiver, name);
        };
    }

    private static JsValue setter(JsDate receiver, String name) {
        if (applySet(Instant.EPOCH.atZone(ZoneOffset.UTC), name, 0) == null) {
            return null;
        }
        return new JsNativeFunction(name, (_, args) -> {
            final var base = receiver.isValid() ? receiver.atUtc() : Instant.EPOCH.atZone(ZoneOffset.UTC);
            receiver.setTime(Objects.requireNonNull(applySet(base, name, intArg(args, 0, 0))).toInstant().toEpochMilli());
            return new JsNumber(receiver.getTime());
        });
    }

    private static ZonedDateTime applySet(ZonedDateTime base, String name, int value) {
        return switch (name) {
            case "setFullYear", "setUTCFullYear" -> base.withYear(value);
            case "setMonth", "setUTCMonth" -> base.withDayOfMonth(1).plusMonths(value);
            case "setDate", "setUTCDate" -> base.withDayOfMonth(1).plusDays(value - 1L);
            case "setHours", "setUTCHours" -> base.withHour(value);
            case "setMinutes", "setUTCMinutes" -> base.withMinute(value);
            case "setSeconds", "setUTCSeconds" -> base.withSecond(value);
            case "setMilliseconds", "setUTCMilliseconds" -> base.withNano(value * 1_000_000);
            default -> null;
        };
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

    private static JsValue toJSON(JsDate receiver) {
        final var iso = receiver.toISOString();
        return iso == null ? JsNull.getInstance() : new JsString(iso);
    }

    private static int intArg(List<JsValue> args, int position, int fallback) {
        if (position >= args.size() || args.get(position) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(position));
        return Double.isNaN(value) ? 0 : (int) value;
    }

    private static String str(List<JsValue> args) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
    }
}
