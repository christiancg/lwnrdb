package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
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
    private static final byte RETRACT_MARK = '-';
    private static final String RETRACT_PREFIX = "-";

    record Loaded(TombstoneChunks chunks, int lines, boolean dropped) {
        boolean unrecognised() {
            return dropped && lines == 0;
        }
    }

    private TombstoneFile() {
    }

    @FunctionalInterface
    private interface LineVisitor {
        void visit(int start, int end);
    }

    static Loaded read(File file) throws IOException {
        final var bytes = Files.readAllBytes(file.toPath());
        final var retracted = retractsIn(bytes);
        final var builder = new TombstoneChunks.Builder(bytes.length / AVERAGE_LINE_BYTES, bytes.length);
        final var counts = new int[2];
        forEachLine(bytes, (start, end) -> {
            if (parseLine(file, bytes, start, end, builder, retracted)) {
                counts[0]++;
            } else {
                counts[1]++;
            }
        });
        return new Loaded(builder.build(), counts[0], counts[1] > 0);
    }

    static String retractLineOf(String id, long version) {
        return FieldIndexEntry.escapeIndexToken(id) + Globals.ID_SEPARATOR + RETRACT_PREFIX + version;
    }

    private static void forEachLine(byte[] bytes, LineVisitor visitor) {
        var start = 0;
        while (start < bytes.length) {
            var end = start;
            while (end < bytes.length && bytes[end] != '\n' && bytes[end] != '\r') {
                end++;
            }
            if (end > start) {
                visitor.visit(start, end);
            }
            start = end + 1;
        }
    }

    private static Set<TombstoneRetract> retractsIn(byte[] bytes) {
        final var retracts = new HashSet<TombstoneRetract>();
        forEachLine(bytes, (start, end) -> {
            final var retract = retractOf(bytes, start, end);
            if (retract != null) {
                retracts.add(retract);
            }
        });
        return retracts;
    }

    private static TombstoneRetract retractOf(byte[] bytes, int start, int end) {
        final var separator = separatorOf(bytes, start, end);
        if (separator == start || separator + 1 >= end || bytes[separator + 1] != RETRACT_MARK) {
            return null;
        }
        try {
            return new TombstoneRetract(idOf(bytes, start, separator), versionOf(bytes, separator + 2, end));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static int separatorOf(byte[] bytes, int start, int end) {
        var separator = start;
        while (separator < end && bytes[separator] != SEPARATOR) {
            separator++;
        }
        return separator;
    }

    private static String idOf(byte[] bytes, int start, int separator) {
        return FieldIndexEntry.unescapeIndexToken(new String(bytes, start, separator - start, StandardCharsets.UTF_8));
    }

    private static boolean parseLine(File file, byte[] bytes, int start, int end, TombstoneChunks.Builder builder,
            Set<TombstoneRetract> retracted) {
        final var separator = separatorOf(bytes, start, end);
        if (separator == start || separator == end) {
            logger.warning("Removing malformed tombstone entry in " + file.getName() + ": no separator found");
            return false;
        }
        final var retract = separator + 1 < end && bytes[separator + 1] == RETRACT_MARK;
        final long version;
        try {
            version = versionOf(bytes, separator + (retract ? 2 : 1), end);
        } catch (NumberFormatException e) {
            logger.warning("Removing malformed tombstone entry in " + file.getName() + ": " + e.getMessage());
            return false;
        }
        if (retract || !retracted.isEmpty()
                && retracted.contains(new TombstoneRetract(idOf(bytes, start, separator), version))) {
            return true;
        }
        if (TombstoneKeys.isAscii(bytes, start, separator) && !containsBackslash(bytes, start, separator)) {
            builder.add(bytes, start, separator, version);
        } else {
            builder.add(idOf(bytes, start, separator), version);
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
