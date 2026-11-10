package io.github.christiancg.lwnrdb.client.scripting;

public enum TriggerTiming {
    AFTER("after"), BEFORE("before");

    private final String wireName;

    TriggerTiming(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
