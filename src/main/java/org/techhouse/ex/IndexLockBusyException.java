package org.techhouse.ex;

public class IndexLockBusyException extends CollectionBusyException {
    private final String indexIdentifier;
    private final long waitedMillis;

    public IndexLockBusyException(String indexIdentifier, long waitedMillis) {
        super(indexIdentifier, waitedMillis);
        this.indexIdentifier = indexIdentifier;
        this.waitedMillis = waitedMillis;
    }

    @Override
    public String getMessage() {
        return "Index " + indexIdentifier + " stayed locked for " + waitedMillis
                + "ms; skipping it rather than waiting on it";
    }
}
