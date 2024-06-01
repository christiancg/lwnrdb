package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson.EJson;
import org.techhouse.ejson.JsonObject;
import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;
import org.techhouse.ejson2.type_adapters.TypeAdapterFactory;

//TODO: remove this class when the old EJson is deleted
public class OldJsonObjectTypeAdapter implements TypeAdapter<JsonObject> {
    private final EJson eJson = new EJson();

    @Override
    public String toJson(JsonObject value) {
        return value.toString();
    }

    @Override
    public JsonObject fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.OBJECT) {
            return eJson.fromJson(
                    TypeAdapterFactory.getAdapter(
                            org.techhouse.ejson2.elements.JsonObject.class)
                            .toJson(value.asJsonObject()), JsonObject.class);
        }
        return null;
    }
}
