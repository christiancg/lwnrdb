package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;

final class PageTails {
    private static final byte[] NO_SEPARATOR = new byte[0];

    private PageTails() {
    }

    record AppendPoint(long length, byte[] separator, boolean exists) {
    }

    static AppendPoint appendPoint(File page) throws IOException {
        final var terminatedAt = PageLengths.terminatedLength(page);
        if (terminatedAt != PageLengths.UNKNOWN) {
            return new AppendPoint(terminatedAt, NO_SEPARATOR, true);
        }
        final var known = PageLengths.knownLength(page);
        if (known != PageLengths.UNKNOWN) {
            return new AppendPoint(known, separatorAfter(page, known), true);
        }
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(page.toPath(), BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return new AppendPoint(0L, NO_SEPARATOR, false);
        }
        return new AppendPoint(attributes.size(), separatorAfter(page, attributes.size()), true);
    }

    private static byte[] separatorAfter(File page, long length) throws IOException {
        return length == 0L ? NO_SEPARATOR : FileLocks.separatorBeforeAppend(page).getBytes(StandardCharsets.UTF_8);
    }
}
