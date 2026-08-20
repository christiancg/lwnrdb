package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public enum RoundingMode {
    CEIL, FLOOR, TRUNC, HALF_EXPAND, HALF_CEIL, HALF_FLOOR, HALF_TRUNC, HALF_EVEN;

    public static RoundingMode parse(String value) {
        return switch (value) {
            case "ceil" -> CEIL;
            case "floor" -> FLOOR;
            case "trunc" -> TRUNC;
            case "halfExpand" -> HALF_EXPAND;
            case "halfCeil" -> HALF_CEIL;
            case "halfFloor" -> HALF_FLOOR;
            case "halfTrunc" -> HALF_TRUNC;
            case "halfEven" -> HALF_EVEN;
            default -> throw new RangeErrorException("Invalid rounding mode: " + value);
        };
    }
}
