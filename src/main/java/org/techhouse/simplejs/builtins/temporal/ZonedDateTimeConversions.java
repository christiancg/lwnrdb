package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.MonthCode.pad2;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readCalendarNameOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readRoundingModeOption;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.readOffsetDisplayOption;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.readTimeZoneNameOption;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeRounding.roundToLocalFractionalDigits;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeRounding.roundToLocalUnit;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.digitsForUnit;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.requireSecondOrSmallerUnit;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ZonedDateTimeConversions {
    public static JsValue toPlainDate(JsTemporalZonedDateTime receiver) {
        return new JsTemporalPlainDate(receiver.isoFieldsAtLocal().date());
    }

    public static JsValue toPlainTime(JsTemporalZonedDateTime receiver) {
        return new JsTemporalPlainTime(receiver.isoFieldsAtLocal().time());
    }

    public static JsValue toPlainDateTime(JsTemporalZonedDateTime receiver) {
        final var fields = receiver.isoFieldsAtLocal();
        return new JsTemporalPlainDateTime(fields.date(), fields.time());
    }

    public static JsValue toPlainYearMonth(JsTemporalZonedDateTime receiver) {
        final var date = receiver.isoFieldsAtLocal().date();
        return new JsTemporalPlainYearMonth(new Iso8601Fields(date.year(), date.month(), 1));
    }

    public static JsValue toPlainMonthDay(JsTemporalZonedDateTime receiver) {
        final var date = receiver.isoFieldsAtLocal().date();
        return new JsTemporalPlainMonthDay(
                new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR, date.month(), date.day()));
    }

    public static JsValue getISOFields(JsTemporalZonedDateTime receiver) {
        final var fields = receiver.isoFieldsAtLocal();
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(fields.date().day()));
        obj.set("isoMonth", new JsNumber(fields.date().month()));
        obj.set("isoYear", new JsNumber(fields.date().year()));
        final var t = fields.time();
        obj.set("isoHour", new JsNumber(t.hour()));
        obj.set("isoMinute", new JsNumber(t.minute()));
        obj.set("isoSecond", new JsNumber(t.second()));
        obj.set("isoMillisecond", new JsNumber(t.millisecond()));
        obj.set("isoMicrosecond", new JsNumber(t.microsecond()));
        obj.set("isoNanosecond", new JsNumber(t.nanosecond()));
        obj.set("offset", new JsString(TemporalFormatter.formatOffset(receiver.offset())));
        obj.set("timeZone", new JsString(receiver.timeZoneId()));
        return obj;
    }

    public static JsValue toStringMethod(JsTemporalZonedDateTime receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        final var fsdValue = optionOrUndefined(optionsArg, "fractionalSecondDigits", ops);
        Integer fsdDigits = null;
        if (!(fsdValue instanceof JsUndefined)) {
            if (fsdValue instanceof JsNumber) {
                final var numeric = JsCoercion.toNumber(fsdValue, ops);
                if (Double.isNaN(numeric)) {
                    throw new RangeErrorException("fractionalSecondDigits must not be NaN");
                }
                final var floored = (int) Math.floor(numeric);
                if (floored < 0 || floored > 9) {
                    throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\", got " + floored);
                }
                fsdDigits = floored;
            } else if (!"auto".equals(JsCoercion.toStr(fsdValue, ops))) {
                throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\"");
            }
        }
        final var offsetOption = readOffsetDisplayOption(optionsArg, ops);
        final var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnitValue = optionOrUndefined(optionsArg, "smallestUnit", ops);
        Unit smallestUnit = null;
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        }
        final var timeZoneOption = readTimeZoneNameOption(optionsArg, ops);

        if (smallestUnit == Unit.MINUTE) {
            final var zoned = roundToLocalUnit(receiver, Unit.MINUTE, 1, mode);
            final var fields = zoned.isoFieldsAtLocal();
            final var offsetText = TemporalFormatter.formatOffset(zoned.offset());
            final var sb = new StringBuilder();
            sb.append(TemporalFormatter.formatDate(fields.date())).append('T').append(pad2(fields.time().hour()))
                    .append(':').append(pad2(fields.time().minute()));
            if (offsetOption != TemporalFormatter.OffsetOption.NEVER) {
                sb.append(offsetText);
            }
            if (timeZoneOption != TemporalFormatter.TimeZoneNameOption.NEVER) {
                sb.append('[');
                if (timeZoneOption == TemporalFormatter.TimeZoneNameOption.CRITICAL) {
                    sb.append('!');
                }
                sb.append(zoned.timeZoneId()).append(']');
            }
            sb.append(TemporalFormatter.formatCalendarAnnotation(calendarName));
            return new JsString(sb.toString());
        }
        JsTemporalZonedDateTime zoned = receiver;
        Integer fractionDigits = null;
        if (smallestUnit != null) {
            requireSecondOrSmallerUnit(smallestUnit);
            zoned = roundToLocalUnit(receiver, smallestUnit, 1, mode);
            fractionDigits = digitsForUnit(smallestUnit);
        } else if (fsdDigits != null) {
            zoned = roundToLocalFractionalDigits(receiver, fsdDigits, mode);
            fractionDigits = fsdDigits;
        }
        final var fields = zoned.isoFieldsAtLocal();
        final var offsetText = TemporalFormatter.formatOffset(zoned.offset());
        return new JsString(TemporalFormatter.formatZonedDateTime(fields.date(), fields.time(), fractionDigits,
                offsetText, zoned.timeZoneId(), timeZoneOption, offsetOption, calendarName));
    }

    private ZonedDateTimeConversions() {
    }
}
