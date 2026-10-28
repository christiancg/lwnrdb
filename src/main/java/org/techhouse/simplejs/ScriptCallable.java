package org.techhouse.simplejs;

import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;

public interface ScriptCallable extends AutoCloseable {
    JsonBaseElement apply(JsonObject document, Set<String> mutableRoots);

    boolean test(JsonObject document, Set<String> mutableRoots);

    JsonBaseElement apply(JsonBaseElement accumulator, JsonObject document);

    JsonBaseElement applyWithContext(JsonObject document, JsonObject context);

    @Override
    void close();
}
