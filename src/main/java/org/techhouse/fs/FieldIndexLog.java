package org.techhouse.fs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.log.Logger;
import org.techhouse.utils.OwnerOnlyPermissions;

final class FieldIndexLog {
    private static final Logger logger = Logger.logFor(FieldIndexLog.class);
    private static final String ADD = "+";
    private static final String REMOVE = "-";
    private static final int PREFIX_LENGTH = ADD.length() + Globals.ID_SEPARATOR.length();
    private static final String APPEND_ROLLBACK_FAILURE = "Could not roll back a failed field index log append;"
            + " REINDEX rebuilds the index from the stored documents";

    private FieldIndexLog() {
    }

    enum Replayed {
        CLEAN, ABSENT, UNRECOGNISED
    }

    static boolean append(File base, File log, List<IndexLogChange> changes) throws IOException {
        final var content = encode(changes);
        if (content.size() > 0) {
            FileLocks.cutUnterminatedTail(log);
            final var lengthBeforeAppend = log.exists() ? log.length() : 0L;
            var appended = false;
            try {
                OwnerOnlyPermissions.ensureOwnerOnlyFile(log.toPath());
                try (var out = new FileOutputStream(log, true)) {
                    content.writeTo(out);
                }
                appended = true;
            } finally {
                if (!appended && log.length() != lengthBeforeAppend) {
                    FileLocks.truncateTo(log, lengthBeforeAppend, APPEND_ROLLBACK_FAILURE);
                }
            }
        }
        final var baseBytes = base.exists() ? base.length() : 0L;
        return log.exists() && log.length() >= Math.max(baseBytes, Globals.FIELD_INDEX_LOG_MIN_BYTES);
    }

    private static ByteArrayOutputStream encode(List<IndexLogChange> changes) throws IOException {
        final var out = new ByteArrayOutputStream();
        for (final var change : changes) {
            writeLine(out, ADD, change.fileKey(), change.added());
            writeLine(out, REMOVE, change.fileKey(), change.removed());
        }
        return out;
    }

    private static void writeLine(ByteArrayOutputStream out, String sign, String fileKey, List<String> ids)
            throws IOException {
        if (ids.isEmpty()) {
            return;
        }
        final var line = new StringBuilder(sign).append(Globals.ID_SEPARATOR).append(fileKey);
        for (final var id : ids) {
            line.append(Globals.ID_SEPARATOR).append(FieldIndexEntry.escapeIndexToken(id));
        }
        out.write(line.append(Globals.NEWLINE).toString().getBytes(StandardCharsets.UTF_8));
    }

    static <T> Replayed replay(File log, LinkedHashMap<String, FieldIndexEntry<T>> byKey, String label,
            Function<String, FieldIndexEntry<T>> parser) throws IOException {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(log.toPath());
        } catch (NoSuchFileException e) {
            return Replayed.ABSENT;
        }
        var parsed = 0;
        var dropped = false;
        var start = 0;
        while (start < bytes.length) {
            final var end = FileLocks.lineEnd(bytes, start);
            if (end >= bytes.length) {
                logger.warning("Ignoring the unterminated last line of " + log.getName()
                        + ": it was torn by an interrupted append");
                break;
            }
            if (end > start) {
                if (apply(new String(bytes, start, end - start, StandardCharsets.UTF_8), byKey, parser)) {
                    parsed++;
                } else {
                    dropped = true;
                    logger.warning("Ignoring a malformed " + label + " index log line in " + log.getName()
                            + ". Run REINDEX on this collection to rebuild it from the stored documents.");
                }
            }
            start = FileLocks.nextLineStart(bytes, end);
        }
        return dropped && parsed == 0 ? Replayed.UNRECOGNISED : Replayed.CLEAN;
    }

    private static <T> boolean apply(String line, LinkedHashMap<String, FieldIndexEntry<T>> byKey,
            Function<String, FieldIndexEntry<T>> parser) {
        final var adds = line.startsWith(ADD + Globals.ID_SEPARATOR);
        if (!adds && !line.startsWith(REMOVE + Globals.ID_SEPARATOR)) {
            return false;
        }
        final FieldIndexEntry<T> change;
        try {
            change = parser.apply(line.substring(PREFIX_LENGTH));
        } catch (RuntimeException e) {
            return false;
        }
        final var key = FieldIndexEntry.fileKeyOf(change.getValue());
        final var existing = byKey.get(key);
        if (adds) {
            if (existing == null) {
                byKey.put(key, change);
            } else {
                existing.getIds().addAll(change.getIds());
            }
        } else if (existing != null) {
            existing.getIds().removeAll(change.getIds());
        }
        return true;
    }
}
