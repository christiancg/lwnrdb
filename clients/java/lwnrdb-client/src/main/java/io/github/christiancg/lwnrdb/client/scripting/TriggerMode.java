package io.github.christiancg.lwnrdb.client.scripting;

public enum TriggerMode {
    DOCUMENT("document"), BATCH("batch");

    private final String wireName;

    TriggerMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
