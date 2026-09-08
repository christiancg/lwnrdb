package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public enum Disambiguation {
    COMPATIBLE, EARLIER, LATER, REJECT;

    public static Disambiguation parse(String value) {
        return switch (value) {
            case "compatible" -> COMPATIBLE;
            case "earlier" -> EARLIER;
            case "later" -> LATER;
            case "reject" -> REJECT;
            default -> throw new RangeErrorException("Invalid disambiguation option: " + value);
        };
    }
}
