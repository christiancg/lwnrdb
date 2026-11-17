package io.github.christiancg.lwnrdb.client.values;

public enum CustomType {
    DATETIME("datetime"), TIME("time"), GEO("geo"), VECTOR("vector");

    private final String wireName;

    CustomType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
