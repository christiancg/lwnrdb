package org.techhouse.ops;

import org.techhouse.concurrency.SharedByteBudget;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;

public class AggregateHeldBudget extends SharedByteBudget {
    private static final Configuration configuration = Configuration.getInstance();

    public AggregateHeldBudget() {
        super(() -> configuration.sizeValue(ConfigKey.AGGREGATE_TOTAL_HELD_BYTES));
    }
}
