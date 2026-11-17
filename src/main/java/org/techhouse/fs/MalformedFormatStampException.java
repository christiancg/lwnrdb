package org.techhouse.fs;

import java.io.IOException;
import java.nio.file.Path;

public final class MalformedFormatStampException extends IOException {
    public MalformedFormatStampException(Path path, String problem) {
        super(path.toAbsolutePath() + " is not a valid storage format stamp: " + problem);
    }
}
