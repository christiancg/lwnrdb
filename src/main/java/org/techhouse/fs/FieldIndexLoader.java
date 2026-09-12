package org.techhouse.fs;

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
        final var lines = FileLocks.readAllLinesIfExists(indexFile);
        if (lines == null) {
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
                        + e.getMessage());
            }
        }
        if (dropped) {
            FileLocks.rewriteFileAtomically(indexFile.toPath(), keepLines);
        }
        entries.sort(order);
        return entries;
    }

    private static <T> Comparator<FieldIndexEntry<T>> byIndexedValue() {
        return (o1, o2) -> switch ((Object) o1.getValue()) {
            case Number n -> Double.compare(n.doubleValue(), ((Number) o2.getValue()).doubleValue());
            case Boolean b -> Boolean.compare(b, (Boolean) o2.getValue());
            case JsonCustom<?> c -> {
                final var customClass = c.getClass();
                //noinspection unchecked
                yield customClass.cast(c).compare(customClass.cast(o2.getValue()).getCustomValue());
            }
            default -> ((String) o1.getValue()).compareToIgnoreCase((String) o2.getValue());
        };
    }
}
