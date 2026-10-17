package org.techhouse.ejson.validate;

import java.util.ArrayList;
import java.util.List;

final class CappedErrors extends ArrayList<String> {
    static final int MAX_REPORTED_ERRORS = 100;
    private long dropped;

    @Override
    public boolean add(String error) {
        if (size() < MAX_REPORTED_ERRORS) {
            return super.add(error);
        }
        dropped++;
        return false;
    }

    List<String> reported() {
        if (dropped == 0) {
            return this;
        }
        final var reported = new ArrayList<>(this);
        reported.add("... and " + dropped + " more");
        return reported;
    }
}
