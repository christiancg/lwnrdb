package org.techhouse.ejson2;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.internal.JsonReader;
import org.techhouse.ejson2.internal.JsonWriter;
import org.techhouse.ejson2.type_adapters.TypeAdapterFactory;
import org.techhouse.ejson2.type_adapters.impl.*;

import java.math.BigDecimal;
import java.math.BigInteger;

public class EJson {
    private final JsonReader reader = new JsonReader();
    private final JsonWriter writer = new JsonWriter();

    public EJson() {
        registerTypes();
    }

    private void registerTypes() {
        // boolean types
        final var booleanTypeAdapter = new BooleanTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(Boolean.class, booleanTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(boolean.class, booleanTypeAdapter);
        // number types
        final var numberTypeAdapter = new NumberTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(short.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Short.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(int.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Integer.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(long.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Long.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(BigInteger.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(float.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Float.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(double.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Double.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(BigDecimal.class, numberTypeAdapter);
        // String types
        final var stringTypeAdapter = new StringTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(String.class, stringTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(StringBuilder.class, stringTypeAdapter);
        // ejson types
        final var jsonBaseElementTypeAdapter = new JsonBaseElementTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(JsonBoolean.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonDouble.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonNull.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonString.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonArray.class, jsonBaseElementTypeAdapter);
        // JsonObject type
        TypeAdapterFactory.registerTypeAdapter(JsonObject.class, new JsonObjectTypeAdapter());
        // TODO: remove this Old Json Object adapter
        TypeAdapterFactory.registerTypeAdapter(org.techhouse.ejson.JsonObject.class, new OldJsonObjectTypeAdapter());
        // TODO: remove this Old Json Element adapter
        TypeAdapterFactory.registerTypeAdapter(org.techhouse.ejson.JsonElement.class, new OldJsonElementTypeAdapter());
    }

    public <T> T fromJson(String jsonString, Class<T> tClass)
            throws Exception {
        return reader.fromJson(jsonString, tClass);
    }

    public <T> T fromJson(JsonBaseElement jsonObject, Class<T> tClass)
            throws Exception {
        final var adapter = TypeAdapterFactory.getAdapter(tClass);
        return adapter.fromJson(jsonObject);
    }

    public <T> String toJson(T obj) {
        final Class<T> clazz = (Class<T>) obj.getClass();
        return writer.toJson(obj, clazz);
    }
}
