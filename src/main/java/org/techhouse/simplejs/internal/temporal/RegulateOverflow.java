package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public enum RegulateOverflow {
    CONSTRAIN, REJECT;

    public static RegulateOverflow parse(String value) {
        return switch (value) {
            case "constrain" -> CONSTRAIN;
            case "reject" -> REJECT;
            default -> throw new RangeErrorException("Invalid overflow value: " + value);
        };
    }
}
