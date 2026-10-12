package org.techhouse.simplejs.internal.regex;

@FunctionalInterface
public interface RegexStepBudget {
    RegexStepBudget UNLIMITED = () -> {
    };

    void step();
}
