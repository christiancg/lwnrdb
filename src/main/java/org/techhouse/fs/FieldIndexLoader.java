package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.log.Logger;

// Index files are written non-atomically, so a crash mid-write can leave a torn line: every loader
// here drops it and rewrites the survivors, rather than failing every later read.
final class FieldIndexLoader {
    private static final Logger logger = Logger.logFor(FieldIndexLoader.class);

    private final FilePaths paths;

    FieldIndexLoader(FilePaths paths) {
        this.paths = paths;
    }

    <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String dbName, String collName, String fieldName,
            Class<T> indexType) throws IOException {
        return load(dbName, collName, IndexKind.fileLabel(indexType), fieldName, "field",
                line -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, line, indexType), byIndexedValue());
    }

    // Sorted by hash so SearchUtils' binary search works.
    List<FieldIndexEntry<String>> readWholeHashIndexFile(String dbName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        return load(dbName, collName, kind.label(), fieldName, "hash",
                line -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, line, String.class),
                Comparator.comparing(FieldIndexEntry::getValue, String::compareToIgnoreCase));
    }

    private <T> List<FieldIndexEntry<T>> load(String dbName, String collName, String indexTypeLabel, String fieldName,
            String label, Function<String, FieldIndexEntry<T>> parser, Comparator<FieldIndexEntry<T>> order)
            throws IOException {
        final var indexFile = paths.indexFile(dbName, collName, fieldName, indexTypeLabel);
        if (indexFile == null || !indexFile.exists()) {
            return null;
        }
        final var readLock = FileLocks.lockFor(indexFile).readLock();
        readLock.lock();
        final ParsedIndex<T> parsed;
        try {
            parsed = parseIndex(indexFile, label, parser);
        } finally {
            readLock.unlock();
        }
        if (parsed == null) {
            return null;
        }
        if (parsed.unrecognised()) {
            logger.error("No line in " + indexFile.getName() + " could be read as a " + label
                    + " index entry, so it is not the file this loader expects; leaving it untouched."
                    + " Run REINDEX on this collection to rebuild it from the stored documents.");
            return null;
        }
        if (!parsed.dropped()) {
            parsed.entries().sort(order);
            return parsed.entries();
        }
        final var writeLock = FileLocks.lockFor(indexFile).writeLock();
        writeLock.lock();
        try {
            final var reparsed = parseIndex(indexFile, label, parser);
            if (reparsed == null) {
                return null;
            }
            if (reparsed.dropped()) {
                FileLocks.rewriteFileAtomically(indexFile.toPath(), reparsed.lines());
            }
            reparsed.entries().sort(order);
            return reparsed.entries();
        } finally {
            writeLock.unlock();
        }
    }

    private record ParsedIndex<T>(List<FieldIndexEntry<T>> entries, List<String> lines, boolean dropped,
            boolean unrecognised) {
    }

    private <T> ParsedIndex<T> parseIndex(File indexFile, String label, Function<String, FieldIndexEntry<T>> parser)
            throws IOException {
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
        return new ParsedIndex<>(entries, keepLines, dropped, dropped && entries.isEmpty());
    }

    private static <T> Comparator<FieldIndexEntry<T>> byIndexedValue() {
        return (o1, o2) -> switch ((Object) o1.getValue()) {
            case Number n -> Double.compare(n.doubleValue(), ((Number) o2.getValue()).doubleValue());
            case Boolean b -> Boolean.compare(b, (Boolean) o2.getValue());
            case JsonCustom<?> c -> {
                final var customClass = c.getClass();
                //noinspection unchecked
                yield customClass.cast(c).compareToCustom(customClass.cast(o2.getValue()));
            }
            default -> ((String) o1.getValue()).compareToIgnoreCase((String) o2.getValue());
        };
    }
}
