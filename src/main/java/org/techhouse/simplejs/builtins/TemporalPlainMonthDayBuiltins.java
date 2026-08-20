package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.function.Function;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.PlainMonthDay}: a thin, "iso8601"-calendar-only projection of {@code PlainDate}
 * onto month+day (see the feature plan's scope-defining finding). Unlike {@code PlainYearMonth},
 * this type has no calendar arithmetic at all per spec - a bare month+day cannot be added to or
 * differenced against a duration without a year to anchor it, so there is deliberately no
 * {@code add}/{@code subtract}/{@code until}/{@code since}, no {@code compare} static, and no
 * numeric {@code month} accessor (only {@code monthCode}, since a bare month number is ambiguous
 * without a year in a general calendar - kept for symmetry with the wider Temporal type family even
 * though it makes no practical difference for the ISO-only calendar this engine implements).
 */
public final class TemporalPlainMonthDayBuiltins {
    public static final List<String> NAMES = List.of("with", "equals", "toPlainDate", "toString", "toJSON",
            "toLocaleString", "getISOFields", "valueOf");

    private TemporalPlainMonthDayBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainMonthDay", (_, args) -> {
            requireNewTarget();
            return construct(args, ops);
        });
        ctor.setLength(2);
        final var from = new JsNativeFunction("from", (_, args) -> toPlainMonthDay(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        return ctor;
    }

    public static JsValue getMethod(JsTemporalPlainMonthDay receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toPlainDate" ->
                new JsNativeFunction("toPlainDate", (_, args) -> toPlainDate(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.PlainMonthDay to a primitive value with valueOf; use equals() "
                                + "instead");
            });
            default -> null;
        };
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "monthCode",
                receiver -> new JsString(monthCode(requireReceiver(receiver, "monthCode").month())));
        installGetter(proto, "day", receiver -> new JsNumber(requireReceiver(receiver, "day").day()));
        installGetter(proto, "calendarId", receiver -> {
            requireReceiver(receiver, "calendarId");
            return new JsString("iso8601");
        });
    }

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }

    private static JsTemporalPlainMonthDay requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainMonthDay md) {
            return md;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainMonthDay wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.PlainMonthDay.prototype." + method + " called on an incompatible receiver");
    }

    private static void requireNewTarget() {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.PlainMonthDay requires 'new'");
        }
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
        final var month = toIntegerField(arg(args, 0), "month", ops);
        final var day = toIntegerField(arg(args, 1), "day", ops);
        final var calendarArg = arg(args, 2);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        final var referenceISOYearArg = arg(args, 3);
        final var referenceISOYear = referenceISOYearArg instanceof JsUndefined
                ? JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR
                : toIntegerField(referenceISOYearArg, "referenceISOYear", ops);
        return new JsTemporalPlainMonthDay(
                IsoCalendar.regulateDate(referenceISOYear, month, day, RegulateOverflow.REJECT));
    }

    // Constructor argument: a bare calendar identifier only; a non-string value is a TypeError.
    private static String requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        return TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    private static JsTemporalPlainMonthDay toPlainMonthDay(JsValue item, InterpreterOps ops) {
        return toPlainMonthDay(item, JsUndefined.getInstance(), ops);
    }

    // ToTemporalMonthDay: accepts an existing PlainMonthDay (referenceISOYear preserved), an ISO
    // month-day or full-date string (referenceISOYear taken from the parse, defaulting to 1972 for
    // the reduced form), or a month-day-like object (day + month/monthCode + optional year,
    // referenceISOYear defaulting to 1972 when absent, regulated per the `overflow` option).
    private static JsTemporalPlainMonthDay toPlainMonthDay(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        final var overflow = readOverflowOption(optionsArg, ops);
        if (item instanceof JsTemporalPlainMonthDay md) {
            return new JsTemporalPlainMonthDay(md.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainMonthDay wrapped) {
            return new JsTemporalPlainMonthDay(wrapped.fields());
        }
        if (item instanceof JsString s) {
            final var parsed = TemporalParser.parseMonthDay(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
            }
            return new JsTemporalPlainMonthDay(parsed.date());
        }
        if (item instanceof JsObject obj) {
            return new JsTemporalPlainMonthDay(monthDayFromFields(obj, overflow, ops));
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainMonthDay");
    }

    private static Iso8601Fields monthDayFromFields(JsObject obj, RegulateOverflow overflow, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var month = resolveMonth(obj, ops);
        final var day = requiredIntegerField(obj, "day", ops);
        final var year = fieldOrDefault(obj, "year", JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR, ops);
        return IsoCalendar.regulateDate(year, month, day, overflow);
    }

    // Property-bag `calendar` field: accepts a bare identifier or a full ISO string carrying (or
    // defaulting) a u-ca annotation. A `calendar` that is itself a Temporal object is taken via its
    // fast path (its calendar is implicitly "iso8601" in this ISO-only engine, so its
    // `calendar`/`calendarId` getters are never read, matching ToTemporalCalendar's own
    // internal-slot fast path).
    private static void requireValidCalendarField(JsObject obj, InterpreterOps ops) {
        final var calendarValue = ops.getMember(obj, new JsString("calendar"));
        if (calendarValue instanceof JsUndefined || calendarValue instanceof JsTemporalPlainDate
                || calendarValue instanceof JsTemporalPlainDateTime || calendarValue instanceof JsTemporalPlainMonthDay
                || calendarValue instanceof JsTemporalPlainYearMonth
                || calendarValue instanceof JsTemporalZonedDateTime) {
            return;
        }
        if (!(calendarValue instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.canonicalizeFlexible(s.getValue());
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

    private static JsValue with(JsTemporalPlainMonthDay receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainMonthDay.prototype.with argument must be an object");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var year = fieldOrDefault(fieldsLike, "year", receiver.referenceISOYear(), ops);
        final var month = resolveMonthWith(fieldsLike, receiver.month(), ops);
        final var day = fieldOrDefault(fieldsLike, "day", receiver.day(), ops);
        return new JsTemporalPlainMonthDay(IsoCalendar.regulateDate(year, month, day, overflow));
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

    // equals compares the receiver's actual [[ISODate]] slot in full (month, day AND
    // referenceISOYear) - two PlainMonthDays built from different reference years are not equal,
    // mirroring CompareISODate.
    private static JsValue equalsMethod(JsTemporalPlainMonthDay receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainMonthDay(otherArg, ops);
        return JsBoolean.of(IsoCalendar.compareIsoDate(receiver.fields(), other.fields()) == 0);
    }

    private static JsValue toPlainDate(JsTemporalPlainMonthDay receiver, JsValue item, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(item)) {
            throw new TypeErrorException(
                    "Temporal.PlainMonthDay.prototype.toPlainDate requires an object with a year property");
        }
        final var year = requiredIntegerField(item, "year", ops);
        return new JsTemporalPlainDate(
                IsoCalendar.regulateDate(year, receiver.month(), receiver.day(), RegulateOverflow.CONSTRAIN));
    }

    private static JsValue toStringMethod(JsTemporalPlainMonthDay receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        return new JsString(TemporalFormatter.formatMonthDay(receiver.fields(), calendarName));
    }

    private static JsValue getISOFields(JsTemporalPlainMonthDay receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.referenceISOYear()));
        return obj;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
