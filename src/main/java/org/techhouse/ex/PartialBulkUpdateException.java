package org.techhouse.ex;

import java.io.IOException;
import org.techhouse.fs.BulkUpdateResult;

public class PartialBulkUpdateException extends IOException {
    private final BulkUpdateResult partialResult;

    public PartialBulkUpdateException(BulkUpdateResult partialResult, Throwable cause) {
        super(cause);
        this.partialResult = partialResult;
    }

    public BulkUpdateResult getPartialResult() {
        return partialResult;
    }
}
