package org.techhouse.ejson.type_adapters.impl;

import java.util.HashMap;
import java.util.Map;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.log.Logger;

public class EnumTypeAdapter<T extends Enum<T>> implements TypeAdapter<T> {

    private final Logger logger = Logger.logFor(EnumTypeAdapter.class);

    private final Class<T> clazz;
    private final Map<String, T> byName;

    public EnumTypeAdapter(Class<T> clazz) {
        this.clazz = clazz;
        final var constants = clazz.getEnumConstants();
        final var names = new HashMap<String, T>(Math.max(4, constants.length * 2));
        for (final var constant : constants) {
            names.put(constant.name(), constant);
        }
        this.byName = names;
    }

    @Override
    public String toJson(T value) {
        return "\"" + value + "\"";
    }

    @Override
    public void toJson(T value, StringBuilder out) {
        out.append('"').append(value).append('"');
    }

    @Override
    public T fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.STRING) {
            final var name = value.asJsonString().getValue();
            final var constant = byName.get(name);
            if (constant == null) {
                logger.error("Error while parsing enum value: ",
                        new IllegalArgumentException("No enum constant " + clazz.getName() + "." + name));
            }
            return constant;
        }
        return null;
    }
}
