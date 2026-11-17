package org.techhouse.fs;

import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;

public record FormatStamp(int formatVersion, String writtenBy) {
    public static FormatStamp current() {
        return new FormatStamp(Globals.STORAGE_FORMAT_VERSION, ServerVersion.current());
    }
}
