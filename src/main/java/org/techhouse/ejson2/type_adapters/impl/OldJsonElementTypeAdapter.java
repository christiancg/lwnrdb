package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson.EJson;
import org.techhouse.ejson.JsonElement;
import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;
import org.techhouse.ejson2.type_adapters.TypeAdapterFactory;

//TODO: remove this class when the old EJson is deleted
public class OldJsonElementTypeAdapter implements TypeAdapter<JsonElement> {

    private final EJson eJson = new EJson();

    @Override
    public String toJson(JsonElement value) {
        return eJson.toJson(value);
    }

    @Override
    public JsonElement fromJson(JsonBaseElement value) {
        final var strValue = TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(value);
        return eJson.fromJson(strValue, JsonElement.class);
    }
}
