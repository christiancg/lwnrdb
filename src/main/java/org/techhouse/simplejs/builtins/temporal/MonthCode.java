package org.techhouse.simplejs.builtins.temporal;

import java.util.regex.Pattern;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class MonthCode {
    public static int resolveMonthValue(Integer month, String monthCode) {
        if (monthCode != null) {
            final var resolved = monthCodeValue(monthCode);
            if (month != null && month != resolved) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return resolved;
        }
        if (month != null) {
            return month;
        }
        throw new TypeErrorException("month or monthCode is required");
    }

    public static String requireMonthCodeSyntax(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "string", ops);
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("monthCode must be a string");
        }
        final var code = s.getValue();
        if (!isSyntacticallyValidMonthCode(code)) {
            throw new RangeErrorException("Invalid monthCode: " + code);
        }
        return code;
    }

    public static final Pattern MONTH_CODE_SYNTAX = Pattern.compile("M\\d{2}L?");

    public static String monthCode(int month) {
        return "M" + pad2(month);
    }

    public static boolean isSyntacticallyValidMonthCode(String code) {
        final var length = code.length();
        if (length != 3 && length != 4) {
            return false;
        }
        return code.charAt(0) == 'M' && Character.isDigit(code.charAt(1)) && Character.isDigit(code.charAt(2))
                && (length == 3 || code.charAt(3) == 'L');
    }

    public static int monthCodeNumericValue(String code) {
        if (code.length() == 4) {
            throw new RangeErrorException("Invalid monthCode for the iso8601 calendar: " + code);
        }
        final var value = Integer.parseInt(code.substring(1));
        if (value < 1 || value > 12) {
            throw new RangeErrorException("Invalid monthCode for the iso8601 calendar: " + code);
        }
        return value;
    }

    public static String monthCodeSyntaxChecked(JsValue value, InterpreterOps ops) {
        if (value instanceof JsUndefined) {
            return null;
        }
        final var primitive = InterpreterUtils.isObjectLike(value)
                ? JsCoercion.toPrimitive(value, "string", ops)
                : value;
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("monthCode must be a string");
        }
        final var code = s.getValue();
        if (!MONTH_CODE_SYNTAX.matcher(code).matches()) {
            throw new RangeErrorException("Invalid monthCode: " + code);
        }
        return code;
    }

    public static int monthCodeValue(String code) {
        final var leap = code.endsWith("L");
        final var value = Integer.parseInt(code.substring(1, 3));
        if (leap || value < 1 || value > 12) {
            throw new RangeErrorException("monthCode " + code + " is not valid for the ISO 8601 calendar");
        }
        return value;
    }

    public static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private MonthCode() {
    }
}
