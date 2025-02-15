package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.log.Logger;

import java.lang.reflect.Method;

public class EnumTypeAdapter<T extends Enum<T>> implements TypeAdapter<T> {

    private final Logger logger = Logger.logFor(EnumTypeAdapter.class);

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
                valueOf.setAccessible(true);
                Object enumValue = valueOf.invoke(null, value.asJsonString().getValue());
                return clazz.cast(enumValue);
            } catch (Exception e) {
                logger.error("Error while parsing enum value: ", e);
            }
        }
        return null;
    }
}
