package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson.EJson;
import org.techhouse.ejson.JsonArray;
import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;
import org.techhouse.ejson2.type_adapters.TypeAdapterFactory;

//TODO: remove this class when the old EJson is deleted
public class OldJsonArrayTypeAdapter implements TypeAdapter<JsonArray> {
    private final EJson eJson = new EJson();

    @Override
    public String toJson(JsonArray value) {
        return value.toString();
    }

    @Override
    public JsonArray fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.ARRAY) {
            return eJson.fromJson(
                    TypeAdapterFactory.getAdapter(
                            org.techhouse.ejson2.elements.JsonArray.class)
                            .toJson(value.asJsonArray()), JsonArray.class);
        }
        return null;
    }
}
