package org.techhouse.ejson.validate;

import org.techhouse.ejson.exceptions.SchemaBudgetExceededException;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;

final class SchemaBudget implements RegexStepBudget {
    static final String EXCEEDED_MESSAGE = "Schema validation exceeded its evaluation budget";

    private long remaining;

    SchemaBudget(long steps) {
        this.remaining = steps;
    }

    @Override
    public void step() {
        if (remaining <= 0) {
            throw new SchemaBudgetExceededException(EXCEEDED_MESSAGE);
        }
        remaining--;
    }
}
