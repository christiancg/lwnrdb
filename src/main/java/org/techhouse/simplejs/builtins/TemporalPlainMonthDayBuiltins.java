package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCode;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCodeSyntaxChecked;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.resolveMonthValue;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireCalendarString;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireDateInRange;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireValidCalendarField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requiredYearField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readCalendarNameOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.internal.temporal.TemporalAccessors.installGetter;
import static org.techhouse.simplejs.internal.temporal.TemporalBrand.isTemporalLikeObject;

import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.ReducedFormParser;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalPlainMonthDayBuiltins {
    public static final List<String> NAMES = List.of("with", "equals", "toPlainDate", "toString", "toJSON",
            "toLocaleString", "getISOFields", "valueOf");

    private TemporalPlainMonthDayBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainMonthDay", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.PlainMonthDay", thisArg);
            return withNewTargetPrototype(construct(args, ops), ops);
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

    private static JsTemporalPlainMonthDay construct(List<JsValue> args, InterpreterOps ops) {
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
        final var result = IsoCalendar.regulateDate(referenceISOYear, month, day, RegulateOverflow.REJECT);
        requireDateInRange(result);
        return new JsTemporalPlainMonthDay(result);
    }

    private static JsTemporalPlainMonthDay toPlainMonthDay(JsValue item, InterpreterOps ops) {
        return toPlainMonthDay(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalPlainMonthDay toPlainMonthDay(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainMonthDay md) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(md.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainMonthDay wrapped) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(wrapped.fields());
        }
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, pd.month(), pd.day()));
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, dt.month(), dt.day()));
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            final var date = zdt.isoFieldsAtLocal().date();
            return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, date.month(), date.day()));
        }
        if (item instanceof JsString s) {
            final var parsed = ReducedFormParser.parseMonthDay(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.requireBuiltinCalendar(parsed.calendar());
            }
            readOverflowOption(optionsArg, ops);
            final var date = parsed.date();
            return new JsTemporalPlainMonthDay(
                    new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR, date.month(), date.day()));
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return monthDayFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainMonthDay");
    }

    private static JsTemporalPlainMonthDay monthDayFromFields(JsValue obj, JsValue optionsArg, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var dayValue = ops.getMember(obj, new JsString("day"));
        final var day = dayValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(dayValue, "day", ops);
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final var month = monthValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(monthValue, "month", ops);
        final var monthCode = monthCodeSyntaxChecked(ops.getMember(obj, new JsString("monthCode")), ops);
        final var yearValue = ops.getMember(obj, new JsString("year"));
        final var year = yearValue instanceof JsUndefined
                ? JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR
                : toIntegerField(yearValue, "year", ops);
        if (day == null) {
            throw new TypeErrorException("day is required");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var regulated = IsoCalendar.regulateCalendarDate(year, resolvedMonth, day, overflow);
        return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, regulated.month(), regulated.day()));
    }

    private static int requirePositiveIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var truncated = toIntegerField(value, name, ops);
        if (truncated < 1) {
            throw new RangeErrorException(name + " must be a positive integer, got " + truncated);
        }
        return truncated;
    }

    private static JsValue with(JsTemporalPlainMonthDay receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || isTemporalLikeObject(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainMonthDay.prototype.with argument must be an object");
        }
        final var calendarValue = ops.getMember(fieldsLike, new JsString("calendar"));
        if (!(calendarValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a calendar property");
        }
        final var timeZoneValue = ops.getMember(fieldsLike, new JsString("timeZone"));
        if (!(timeZoneValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a timeZone property");
        }
        final var dayValue = ops.getMember(fieldsLike, new JsString("day"));
        final var day = dayValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(dayValue, "day", ops);
        final var monthValue = ops.getMember(fieldsLike, new JsString("month"));
        final var month = monthValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(monthValue, "month", ops);
        final var monthCode = monthCodeSyntaxChecked(ops.getMember(fieldsLike, new JsString("monthCode")), ops);
        final var yearValue = ops.getMember(fieldsLike, new JsString("year"));
        final var year = yearValue instanceof JsUndefined ? null : (Integer) toIntegerField(yearValue, "year", ops);
        if (day == null && month == null && monthCode == null && year == null) {
            throw new TypeErrorException("with() argument must contain at least one of year, month, monthCode, day");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = month == null && monthCode == null
                ? receiver.month()
                : resolveMonthValue(month, monthCode);
        final var resolvedDay = day != null ? day : receiver.day();
        final var resolvedYear = year != null ? year : receiver.referenceISOYear();
        final var regulated = IsoCalendar.regulateCalendarDate(resolvedYear, resolvedMonth, resolvedDay, overflow);
        return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, regulated.month(), regulated.day()));
    }

    private static JsValue equalsMethod(JsTemporalPlainMonthDay receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainMonthDay(otherArg, ops);
        return JsBoolean.of(IsoCalendar.compareIsoDate(receiver.fields(), other.fields()) == 0);
    }

    private static JsValue toPlainDate(JsTemporalPlainMonthDay receiver, JsValue item, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(item)) {
            throw new TypeErrorException(
                    "Temporal.PlainMonthDay.prototype.toPlainDate requires an object with a year property");
        }
        final var year = requiredYearField(item, ops);
        final var result = IsoCalendar.regulateDate(year, receiver.month(), receiver.day(), RegulateOverflow.CONSTRAIN);
        requireDateInRange(result);
        return new JsTemporalPlainDate(result);
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

}
