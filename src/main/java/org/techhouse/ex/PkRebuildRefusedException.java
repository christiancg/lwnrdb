package org.techhouse.ex;

import java.io.IOException;

public final class PkRebuildRefusedException extends IOException {
    public PkRebuildRefusedException(String reason) {
        super(reason);
    }
}
