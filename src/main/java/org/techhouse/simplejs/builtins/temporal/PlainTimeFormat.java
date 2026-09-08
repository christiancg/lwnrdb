package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.DbTimeBuiltins.member;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.pad2;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeDifference.requireTimeUnit;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeRounding.roundToFractionalDigits;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeRounding.roundToUnit;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.digitsForUnit;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainTimeFormat {
    public static JsValue toStringMethod(JsTemporalPlainTime receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg instanceof JsUndefined) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        Double fsdNumeric = null;
        String fsdString = null;
        final var fsdRaw = member(optionsArg, "fractionalSecondDigits", ops);
        if (!(fsdRaw instanceof JsUndefined)) {
            if (fsdRaw instanceof JsNumber) {
                fsdNumeric = JsCoercion.toNumber(fsdRaw, ops);
            } else {
                fsdString = JsCoercion.toStr(fsdRaw, ops);
            }
        }
        String modeStr = null;
        final var modeValue = member(optionsArg, "roundingMode", ops);
        if (!(modeValue instanceof JsUndefined)) {
            modeStr = JsCoercion.toStr(modeValue, ops);
        }
        String smallestUnitStr = null;
        final var smallestUnitValue = member(optionsArg, "smallestUnit", ops);
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnitStr = JsCoercion.toStr(smallestUnitValue, ops);
        }
        final var mode = modeStr == null ? RoundingMode.TRUNC : RoundingMode.parse(modeStr);
        if (smallestUnitStr != null) {
            final var unit = Unit.parseTemporalUnit(smallestUnitStr);
            requireTimeUnit(unit);
            if (unit == Unit.MINUTE) {
                final var rounded = roundToUnit(receiver.getFields(), Unit.MINUTE, 1, mode);
                return new JsString(pad2(rounded.hour()) + ":" + pad2(rounded.minute()));
            }
            final var rounded = roundToUnit(receiver.getFields(), unit, 1, mode);
            return new JsString(TemporalFormatter.formatTime(rounded, digitsForUnit(unit)));
        }
        if (fsdNumeric != null) {
            final var flooredDouble = Math.floor(fsdNumeric);
            if (Double.isNaN(flooredDouble) || flooredDouble < 0 || flooredDouble > 9) {
                throw new RangeErrorException("fractionalSecondDigits " + fsdNumeric + " floors to " + flooredDouble
                        + " and is out of " + "range");
            }
            final var digits = (int) flooredDouble;
            final var rounded = roundToFractionalDigits(receiver.getFields(), digits, mode);
            return new JsString(TemporalFormatter.formatTime(rounded, digits));
        }
        if (fsdString != null) {
            if (!"auto".equals(fsdString)) {
                throw new RangeErrorException(
                        "fractionalSecondDigits must be 0..9 or \"auto\", got \"" + fsdString + "\"");
            }
            return new JsString(receiver.toString());
        }
        return new JsString(receiver.toString());
    }

    private PlainTimeFormat() {
    }
}
