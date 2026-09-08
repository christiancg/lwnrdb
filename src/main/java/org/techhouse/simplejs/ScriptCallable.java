package org.techhouse.simplejs;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;

public interface ScriptCallable extends AutoCloseable {
    JsonBaseElement apply(JsonObject document);

    JsonBaseElement apply(JsonBaseElement accumulator, JsonObject document);

    JsonBaseElement applyWithContext(JsonObject document, JsonObject context);

    @Override
    void close();
}
