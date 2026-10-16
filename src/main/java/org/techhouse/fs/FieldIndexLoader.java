package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.fs.FileLocks.Parsed;
import org.techhouse.log.Logger;
import org.techhouse.utils.CaseFolding;

// Index files are written non-atomically, so a crash mid-write can leave a torn line: every loader
// here drops it and rewrites the survivors, rather than failing every later read.
public final class FieldIndexLoader {
    private static final Logger logger = Logger.logFor(FieldIndexLoader.class);

    private final FilePaths paths;

    FieldIndexLoader(FilePaths paths) {
        this.paths = paths;
    }

    <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName,
            Class<T> indexType) throws IOException {
        return load(dbName, collName, IndexKind.fileLabel(indexType), fieldName, "field",
                line -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, line, indexType), orderFor(indexType));
    }

    // Sorted by hash so SearchUtils' binary search works.
    List<FieldIndexEntry<String>> readWholeHashIndexFile(String dbName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        return load(dbName, collName, kind.label(), fieldName, "hash",
                line -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, line, String.class), hashOrder());
    }

    private <T> List<FieldIndexEntry<T>> load(String dbName, String collName, String indexTypeLabel, String fieldName,
            String label, Function<String, FieldIndexEntry<T>> parser, Comparator<FieldIndexEntry<T>> order)
            throws IOException {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, indexTypeLabel);
        if (indexFile == null) {
            return null;
        }
        final var logFile = FilePaths.logFileOf(indexFile);
        if (!indexFile.exists() && !logFile.exists()) {
            return null;
        }
        final var entries = readBase(indexFile, label, parser);
        if (entries == null) {
            return null;
        }
        final var replayed = logFile.exists() ? replayLog(indexFile, logFile, entries, label, parser) : entries;
        if (replayed == null) {
            return null;
        }
        replayed.sort(order);
        return replayed;
    }

    private <T> List<FieldIndexEntry<T>> readBase(File indexFile, String label,
            Function<String, FieldIndexEntry<T>> parser) throws IOException {
        if (!indexFile.exists()) {
            return new ArrayList<>();
        }
        final var parsed = FileLocks.readHealing(indexFile, file -> parseIndex(file, label, parser));
        if (parsed == null) {
            return null;
        }
        if (parsed.unrecognised()) {
            logger.error("No line in " + indexFile.getName() + " could be read as a " + label
                    + " index entry, so it is not the file this loader expects; leaving it untouched."
                    + " Run REINDEX on this collection to rebuild it from the stored documents.");
            return null;
        }
        return parsed.entries();
    }

    private static <T> List<FieldIndexEntry<T>> replayLog(File indexFile, File logFile, List<FieldIndexEntry<T>> base,
            String label, Function<String, FieldIndexEntry<T>> parser) throws IOException {
        final var byKey = new LinkedHashMap<String, FieldIndexEntry<T>>(base.size() * 2);
        for (final var entry : base) {
            byKey.put(FieldIndexEntry.fileKeyOf(entry.getValue()), entry);
        }
        final var lock = FileLocks.lockRead(indexFile);
        final FieldIndexLog.Replayed replayed;
        try {
            replayed = FieldIndexLog.replay(logFile, byKey, label, parser);
        } finally {
            lock.unlock();
        }
        if (replayed == FieldIndexLog.Replayed.UNRECOGNISED) {
            logger.error("No line in " + logFile.getName() + " could be read as a " + label + " index log entry, so"
                    + " the index is declined and the field is answered by a scan. Run REINDEX on this collection to"
                    + " rebuild it from the stored documents.");
            return null;
        }
        final var entries = new ArrayList<FieldIndexEntry<T>>(byKey.size());
        for (final var entry : byKey.values()) {
            if (!entry.getIds().isEmpty()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private <T> Parsed<List<FieldIndexEntry<T>>> parseIndex(File indexFile, String label,
            Function<String, FieldIndexEntry<T>> parser) throws IOException {
        final List<String> lines;
        try {
            lines = FileLocks.decodeLines(java.nio.file.Files.readAllBytes(indexFile.toPath()));
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
        final var entries = new ArrayList<FieldIndexEntry<T>>();
        final var keepLines = new ArrayList<String>();
        var dropped = false;
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(parser.apply(line));
                keepLines.add(line);
            } catch (Exception e) {
                dropped = true;
                logger.warning("Removing malformed " + label + " index entry in " + indexFile.getName() + ": "
                        + e.getMessage() + ". Dropped line was: " + line
                        + ". Run REINDEX on this collection to rebuild it from the stored documents.");
            }
        }
        return new Parsed<>(entries, keepLines, dropped, dropped && entries.isEmpty());
    }

    public static <T> Comparator<FieldIndexEntry<T>> orderFor(Class<T> indexType) {
        if (Number.class.isAssignableFrom(indexType)) {
            return Comparator.comparingDouble(entry -> ((Number) entry.getValue()).doubleValue());
        }
        if (indexType == Boolean.class) {
            return (o1, o2) -> Boolean.compare((Boolean) o1.getValue(), (Boolean) o2.getValue());
        }
        if (indexType == String.class) {
            return (o1, o2) -> compareStrings((String) o1.getValue(), (String) o2.getValue());
        }
        return (o1, o2) -> compareCustom((JsonCustom<?>) o1.getValue(), o2.getValue());
    }

    private static int compareStrings(String left, String right) {
        final var folded = CaseFolding.compare(left, right);
        return folded != 0 ? folded : left.compareTo(right);
    }

    public static Comparator<FieldIndexEntry<String>> hashOrder() {
        return Comparator.comparing(FieldIndexEntry::getValue, String::compareToIgnoreCase);
    }

    private static int compareCustom(JsonCustom<?> custom, Object other) {
        final var customClass = custom.getClass();
        //noinspection unchecked
        final var compared = customClass.cast(custom).compareToCustom(customClass.cast(other));
        return compared != 0 ? compared : FieldIndexEntry.fileKeyOf(custom).compareTo(FieldIndexEntry.fileKeyOf(other));
    }
}
