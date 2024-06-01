package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;

import java.lang.reflect.Method;

public class EnumTypeAdapter<T extends Enum<T>> implements TypeAdapter<T> {

    private final Class<T> clazz;

    public EnumTypeAdapter(Class<T> clazz) {
        this.clazz = clazz;
    }

    @Override
    public String toJson(T value) {
        return "\"" + value + "\"";
    }

    @Override
    public T fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.STRING) {
            try {
                Method valueOf = clazz.getMethod("valueOf", String.class);
                Object enumValue = valueOf.invoke(null, value.asJsonString().getValue());
                return clazz.cast(enumValue);
            } catch (Exception ignored) {
                // TODO: probably log error here
            }
        }
        return null;
    }
}
