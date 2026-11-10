package io.github.christiancg.lwnrdb.client.admin;

public enum TriggerRunDecision {
    REPLAY("replay"), DISCARD("discard");

    private final String wireName;

    TriggerRunDecision(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
