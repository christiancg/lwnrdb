package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    static byte[] separatorBefore(File page) throws IOException {
        final var remembered = terminated.get(page.getAbsolutePath());
        if (remembered != null && remembered.equals(tailOf(page))) {
            return NO_SEPARATOR;
        }
        return FileLocks.separatorBeforeAppend(page).getBytes(StandardCharsets.UTF_8);
    }

    static void appended(File page, byte[] lastLine) {
        final var tail = lastLine.length > 0 && lastLine[lastLine.length - 1] == LINE_FEED ? tailOf(page) : null;
        if (tail == null) {
            forget(page);
        } else {
            terminated.put(page.getAbsolutePath(), tail);
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
