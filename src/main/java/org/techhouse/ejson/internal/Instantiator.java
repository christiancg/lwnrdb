package org.techhouse.ejson.internal;

import org.techhouse.ejson.elements.JsonObject;

@FunctionalInterface
public interface Instantiator {
    Object newInstance(JsonObject parsed) throws Exception;
}
