package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.techhouse.data.PkIndexEntry;

final class WalRedoFixture {
    static final String DB = "redoDb";
    static final String COLL = "redoColl";

    final ReplacerFixture rf;

    WalRedoFixture(File root) throws IOException {
        rf = new ReplacerFixture(root, DB, COLL);
        for (final var id : List.of("a", "b", "c")) {
            insert(id);
        }
        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
    }

    void insert(String id) throws IOException {
        rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
    }

    List<PkIndexEntry> redo() throws IOException {
        PowerLossFixture.restart(rf.fx.paths, DB, COLL);
        return WalRedo.redoAll(rf.fx.paths);
    }

    File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    Map<String, String> scanned() throws IOException {
        final var values = new TreeMap<String, String>();
        try (var entries = new DocumentPageStore(rf.fx.paths).streamEntries(DB, COLL)) {
            entries.forEach(entry -> values.merge(entry.get_id(), entry.getData().get("v").asJsonString().getValue(),
                    (first, second) -> first + "," + second));
        }
        return values;
    }

    void assertHolds(Map<String, String> expected) throws IOException {
        assertEquals(expected, scanned(), "a page scan answers every acknowledged write once");
        final var indexed = new TreeMap<String, String>();
        for (final var row : rf.fx.indexedRows()) {
            indexed.put(row.getValue(), rf.fx.readAt(row));
        }
        final var records = new TreeMap<String, String>();
        expected.forEach((id, value) -> records.put(id, StorageFixture.record(id, value)));
        assertEquals(records, indexed, "the pk index points every id at its record");
    }

    static Map<String, String> original() {
        return Map.of("a", "old-a", "b", "old-b", "c", "old-c");
    }

    static Map<String, String> originalWith(String id, String value) {
        final var expected = new TreeMap<>(original());
        expected.put(id, value);
        return expected;
    }
}
