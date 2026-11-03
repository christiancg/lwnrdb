package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.log.Logger;

final class TombstoneFile {
    private static final Logger logger = Logger.logFor(TombstoneFile.class);
    private static final byte SEPARATOR = (byte) Globals.ID_SEPARATOR.charAt(0);
    private static final byte[] SEPARATOR_BYTES = Globals.ID_SEPARATOR.getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE_BYTES = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final int AVERAGE_LINE_BYTES = 32;
    private static final int MAX_PLAIN_DIGITS = 18;

    record Loaded(TombstoneChunks chunks, int lines, boolean dropped) {
        boolean unrecognised() {
            return dropped && lines == 0;
        }
    }

    private TombstoneFile() {
    }

    static Loaded read(File file) throws IOException {
        final var bytes = Files.readAllBytes(file.toPath());
        final var builder = new TombstoneChunks.Builder(bytes.length / AVERAGE_LINE_BYTES, bytes.length);
        var lines = 0;
        var dropped = false;
        var start = 0;
        while (start < bytes.length) {
            var end = start;
            while (end < bytes.length && bytes[end] != '\n' && bytes[end] != '\r') {
                end++;
            }
            if (end > start) {
                if (parseLine(file, bytes, start, end, builder)) {
                    lines++;
                } else {
                    dropped = true;
                }
            }
            start = end + 1;
        }
        return new Loaded(builder.build(), lines, dropped);
    }

    private static boolean parseLine(File file, byte[] bytes, int start, int end, TombstoneChunks.Builder builder) {
        var separator = start;
        while (separator < end && bytes[separator] != SEPARATOR) {
            separator++;
        }
        if (separator == start || separator == end) {
            logger.warning("Removing malformed tombstone entry in " + file.getName() + ": no separator found");
            return false;
        }
        final long version;
        try {
            version = versionOf(bytes, separator + 1, end);
        } catch (NumberFormatException e) {
            logger.warning("Removing malformed tombstone entry in " + file.getName() + ": " + e.getMessage());
            return false;
        }
        if (TombstoneKeys.isAscii(bytes, start, separator) && !containsBackslash(bytes, start, separator)) {
            builder.add(bytes, start, separator, version);
        } else {
            builder.add(FieldIndexEntry
                    .unescapeIndexToken(new String(bytes, start, separator - start, StandardCharsets.UTF_8)), version);
        }
        return true;
    }

    private static long versionOf(byte[] bytes, int from, int to) {
        if (to - from > 0 && to - from <= MAX_PLAIN_DIGITS) {
            var value = 0L;
            var position = from;
            while (position < to && bytes[position] >= '0' && bytes[position] <= '9') {
                value = value * 10 + (bytes[position++] - '0');
            }
            if (position == to) {
                return value;
            }
        }
        return Long.parseLong(new String(bytes, from, to - from, StandardCharsets.UTF_8));
    }

    private static boolean containsBackslash(byte[] bytes, int from, int to) {
        for (var position = from; position < to; position++) {
            if (bytes[position] == '\\') {
                return true;
            }
        }
        return false;
    }

    static void rewrite(File file, TombstoneChunks chunks) throws IOException {
        FileLocks.rewriteFileAtomically(file.toPath(), out -> chunks.forEachEntry((keys, from, to, version) -> {
            if (writableAsIs(keys, from, to)) {
                out.write(keys, from, to - from);
            } else {
                out.write(FieldIndexEntry.escapeIndexToken(TombstoneKeys.decode(keys, from, to))
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.write(SEPARATOR_BYTES);
            out.write(Long.toString(version).getBytes(StandardCharsets.US_ASCII));
            out.write(NEWLINE_BYTES);
        }));
    }

    private static boolean writableAsIs(byte[] keys, int from, int to) {
        for (var position = from; position < to; position++) {
            final var current = keys[position];
            if (current < 0 || current == '\\' || current == '\n' || current == '\r' || current == SEPARATOR) {
                return false;
            }
        }
        return true;
    }
}
