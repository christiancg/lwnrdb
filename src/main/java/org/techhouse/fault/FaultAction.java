package org.techhouse.fault;

public enum FaultAction {
    PAUSE, HALT, DELAY, DROP;

    public static FaultAction parse(String name) {
        for (final var action : values()) {
            if (action.name().equalsIgnoreCase(name)) {
                return action;
            }
        }
        return null;
    }
}
