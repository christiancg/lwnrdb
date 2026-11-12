package org.techhouse.ex;

import java.io.IOException;

public final class IncompatibleStorageFormatException extends IOException {
    public IncompatibleStorageFormatException(String reason) {
        super(reason);
    }

    public IncompatibleStorageFormatException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
