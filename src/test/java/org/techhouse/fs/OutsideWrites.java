package org.techhouse.fs;

import java.io.File;
import org.techhouse.config.Globals;

public final class OutsideWrites {
    private OutsideWrites() {
    }

    public static void madeTo(File file) {
        PageLengths.forget(file);
        PkIndexStore.forgetState(file);
        FieldIndexLog.forgetFilesUnder(file.getAbsoluteFile().getParentFile());
        if (file.getName().endsWith(Globals.WAL_EXTENSION)) {
            WriteAheadLog.forgetUnder(file.getAbsoluteFile().getParentFile());
        }
    }
}
