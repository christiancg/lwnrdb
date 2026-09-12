package org.techhouse.fs;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.log.Logger;
import org.techhouse.utils.ReflectionUtils;

// Index files are written non-atomically, so a crash mid-write can leave a torn line: every loader
// here drops it and rewrites the survivors, rather than failing every later read.
final class FieldIndexLoader {
    private static final Logger logger = Logger.logFor(FieldIndexLoader.class);

    private final FilePaths paths;

    FieldIndexLoader(FilePaths paths) {
        this.paths = paths;
    }

    ConcurrentMap<String, List<FieldIndexEntry<?>>> readAllWholeFieldIndexFiles(String dbName, String collName,
            String fieldName) {
        final var collectionFolder = paths.collectionFolder(dbName, collName);
        if (!collectionFolder.exists()) {
            return null;
        }
        final var indexFiles = collectionFolder.listFiles((_, name) -> name.endsWith(Globals.INDEX_FILE_EXTENSION)
                && !name.contains(Globals.PK_FIELD) && name.contains(fieldName));
        if (indexFiles == null) {
            return null;
        }
        return Arrays.stream(indexFiles).map(file -> {
            try {
                final var type = file.getName().split("-")[2].split("\\.")[0];
                return new AbstractMap.SimpleEntry<>(type, FileLocks.readAllLinesLocked(file));
            } catch (IOException e) {
                return null;
            }
        }).filter(Objects::nonNull).map(entry -> {
            final var className = entry.getKey();
            final var clazz = ReflectionUtils.getClassFromSimpleName(className);
            return new AbstractMap.SimpleEntry<>(className,
                    entry.getValue().stream().map(s -> FieldIndexEntry.fromIndexFileEntry(dbName, collName, s, clazz))
                            .collect(Collectors.toList()));
        }).collect(Collectors.toConcurrentMap(AbstractMap.SimpleEntry::getKey, e -> new ArrayList<>(e.getValue())));
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
        if (!paths.collectionFolder(dbName, collName).exists()) {
            return null;
        }
        final var indexFile = paths.indexFile(dbName, collName, fieldName, indexTypeLabel);
        if (!indexFile.exists()) {
            return null;
        }
        final var entries = new ArrayList<FieldIndexEntry<T>>();
        final var keepLines = new ArrayList<String>();
        var dropped = false;
        for (var line : FileLocks.readAllLinesLocked(indexFile)) {
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
