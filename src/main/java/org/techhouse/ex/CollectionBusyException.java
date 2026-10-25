package org.techhouse.ex;

public class CollectionBusyException extends RuntimeException {
    private final String collectionIdentifier;
    private final long waitedMillis;

    public CollectionBusyException(String collectionIdentifier, long waitedMillis) {
        super(null, null, false, false);
        this.collectionIdentifier = collectionIdentifier;
        this.waitedMillis = waitedMillis;
    }

    @Override
    public String getMessage() {
        return "Collection " + collectionIdentifier + " stayed locked for " + waitedMillis
                + "ms; skipping it rather than waiting on it";
    }
}
