package org.techhouse.simplejs;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;

/**
 * A function a script exported, callable once per document. The module body is evaluated once when the
 * callable is opened, so every call shares one instruction budget, deadline and memory budget: a runaway
 * operator aborts the whole pipeline instead of getting a fresh budget on every row.
 *
 * <p>
 * Every failure - a throw, a sandbox abort, a conversion refusal - surfaces as {@code ScriptCallableException}
 * carrying the same error name {@code ScriptResult} would have reported.
 */
public interface ScriptCallable extends AutoCloseable {
    JsonBaseElement apply(JsonObject document);

    JsonBaseElement apply(JsonBaseElement accumulator, JsonObject document);

    JsonBaseElement applyWithContext(JsonObject document, JsonObject context);

    @Override
    void close();
}
