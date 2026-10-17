package org.techhouse.ejson.internal;

public class JsonReader {

    public <T> T fromJson(String input, Class<T> tClass) {
        return fromJson(input, tClass, Integer.MAX_VALUE);
    }

    public <T> T fromJson(String input, Class<T> tClass, int maxDepth) {
        return fromJson(input, tClass, maxDepth, Integer.MAX_VALUE);
    }

    public <T> T fromJson(byte[] utf8, int start, int end, Class<T> tClass, int maxDepth, int maxTokens) {
        final var root = JsonParser.parse(utf8, start, end, maxDepth, maxTokens);
        return tClass.cast(Assigner.assign(root.asJsonObject(), tClass));
    }

    public <T> T fromJson(String input, Class<T> tClass, int maxDepth, int maxTokens) {
        final var root = JsonParser.parse(input, maxDepth, maxTokens);
        return tClass.cast(Assigner.assign(root.asJsonObject(), tClass));
    }
}
