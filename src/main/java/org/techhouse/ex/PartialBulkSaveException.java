package org.techhouse.ex;

import java.io.IOException;
import org.techhouse.ops.resp.BulkSaveResponse;

public class PartialBulkSaveException extends IOException {
    private final BulkSaveResponse committed;

    public PartialBulkSaveException(BulkSaveResponse committed, Throwable cause) {
        super(cause.getMessage(), cause);
        this.committed = committed;
    }

    public BulkSaveResponse committed() {
        return committed;
    }
}
