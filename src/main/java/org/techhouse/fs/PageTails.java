package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PageTails {
    private static final byte[] NO_SEPARATOR = new byte[0];
    private static final byte LINE_FEED = '\n';
    private static final Map<String, Tail> terminated = new ConcurrentHashMap<>();

    private record Tail(long size, FileTime modified) {
    }

    private PageTails() {
    }

    record AppendPoint(long length, byte[] separator, boolean exists) {
    }

    static AppendPoint appendPoint(File page) throws IOException {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(page.toPath(), BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return new AppendPoint(0L, NO_SEPARATOR, false);
        }
        final var tail = new Tail(attributes.size(), attributes.lastModifiedTime());
        final var separator = tail.equals(terminated.get(page.getAbsolutePath()))
                ? NO_SEPARATOR
                : FileLocks.separatorBeforeAppend(page).getBytes(StandardCharsets.UTF_8);
        return new AppendPoint(tail.size(), separator, true);
    }

    static long knownTerminatedLength(File file) {
        final var remembered = terminated.get(file.getAbsolutePath());
        return remembered != null && remembered.equals(tailOf(file)) ? remembered.size() : -1L;
    }

    static void appended(File page, byte[] lastLine) {
        if (lastLine.length > 0 && lastLine[lastLine.length - 1] == LINE_FEED) {
            appendedWholeLines(page);
        } else {
            forget(page);
        }
    }

    static void appendedWholeLines(File file) {
        final var tail = tailOf(file);
        if (tail == null) {
            forget(file);
        } else {
            terminated.put(file.getAbsolutePath(), tail);
        }
    }

    static void blanked(File page) {
        final var path = page.getAbsolutePath();
        if (terminated.containsKey(path)) {
            final var tail = tailOf(page);
            if (tail == null) {
                terminated.remove(path);
            } else {
                terminated.put(path, tail);
            }
        }
    }

    static void forget(File file) {
        terminated.remove(file.getAbsolutePath());
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        terminated.keySet().removeIf(path -> path.startsWith(prefix));
    }

    private static Tail tailOf(File page) {
        try {
            final var attributes = Files.readAttributes(page.toPath(), BasicFileAttributes.class);
            return new Tail(attributes.size(), attributes.lastModifiedTime());
        } catch (IOException e) {
            return null;
        }
    }
}
