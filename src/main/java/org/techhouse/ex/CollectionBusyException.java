package org.techhouse.ex;

public class CollectionBusyException extends RuntimeException {
    public CollectionBusyException(String collectionIdentifier, long waitedMillis) {
        super("Collection " + collectionIdentifier + " stayed locked for " + waitedMillis
                + "ms; skipping it rather than waiting on it");
    }
}
