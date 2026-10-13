package org.techhouse.simplejs.internal.regex;

@FunctionalInterface
public interface RegexStepBudget {
    RegexStepBudget UNLIMITED = () -> {
    };

    void step();

    default void steps(int count) {
        for (var i = 0; i < count; i++) {
            step();
        }
    }
}
