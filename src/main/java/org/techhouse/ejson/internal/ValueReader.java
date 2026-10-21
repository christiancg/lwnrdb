package org.techhouse.ejson.internal;

import org.techhouse.ejson.elements.JsonBaseElement;

@FunctionalInterface
public interface ValueReader {
    Object read(JsonBaseElement element) throws Exception;
}
