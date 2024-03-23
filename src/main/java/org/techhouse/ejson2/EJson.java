package org.techhouse.ejson2;

public class EJson {
    private final JsonReader reader = new JsonReader();
    private final JsonWriter writer = new JsonWriter();

    public <T> T fromJson(String jsonString, Class<T> tClass)
            throws Exception {
        return reader.fromJson(jsonString, tClass);
    }

    public <T> String toJson(T obj) {
        return writer.toJson(obj);
    }
}
