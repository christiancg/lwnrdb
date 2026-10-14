package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;

public class PkIndexRewriteFromTest {
    private static final String DB = "rewriteDb";
    private static final String COLL = "rewriteColl";
    private static final int SEED_ROWS = 300;

    @TempDir
    File tmp;

    private PkIndexStore store;
    private File indexFile;
    private File oracleFile;
    private List<PkIndexEntry> resident;
    private Random random;
    private int nextId;

    @BeforeEach
    public void setUp() throws IOException {
        final var paths = new FilePaths();
        paths.useDbPath(new File(tmp, "store").getAbsolutePath());
        assertTrue(paths.collectionFolder(DB, COLL).mkdirs());
        store = new PkIndexStore(paths);
        indexFile = paths.pkIndexFile(DB, COLL);
        oracleFile = new File(tmp, "oracle-pk.idx");
        random = new Random(20261013L);
        final var seed = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < SEED_ROWS; i++) {
            seed.add(freshRow());
        }
        store.bulkIndexNewPKValues(DB, COLL, seed);
        Files.copy(indexFile.toPath(), oracleFile.toPath());
        resident = new ArrayList<>(store.readWholePkIndexFile(DB, COLL));
    }

    private PkIndexEntry freshRow() {
        final var id = "id-" + Integer.toString(nextId++ * 7919 % 100_003, 36) + "-" + nextId;
        return new PkIndexEntry(DB, COLL, id, random.nextInt(1_000_000), 20 + random.nextInt(400), random.nextInt(4),
                1_700_000_000_000L + random.nextInt(1000));
    }

    private PkIndexEntry pickResident() {
        return resident.get(random.nextInt(resident.size()));
    }

    private void absorb(PkIndexEntry removed, PkIndexEntry replacement) {
        resident.remove(removed);
        for (final var row : resident) {
            if (row.getPage() == removed.getPage() && row.getPosition() > removed.getPosition()) {
                row.setPosition(row.getPosition() - removed.getLength());
            }
        }
        if (replacement != null) {
            final var at = Collections.binarySearch(resident, replacement.getValue());
            resident.add(-(at + 1), replacement);
        }
    }

    private void update(PkIndexEntry old) throws IOException {
        final var position = old.getPosition() + old.getLength() + random.nextInt(5000);
        final var length = 20 + random.nextInt(400);
        final var version = old.getVersion() + 1 + random.nextInt(50);
        final var updated = store.updateIndexValues(DB, COLL, old.getValue(), position, length, old.getPage(), version,
                resident);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, old.getValue(),
                new PkIndexEntry(DB, COLL, old.getValue(), position, length, old.getPage(), version));
        absorb(old, updated);
    }

    private void delete(PkIndexEntry old) throws IOException {
        store.deleteIndexValue(old, resident);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, old.getValue(), null);
        absorb(old, null);
    }

    private void insert() throws IOException {
        final var row = freshRow();
        store.bulkIndexNewPKValues(DB, COLL, List.of(row));
        FileLocks.appendLines(oracleFile, List.of(row), PkIndexEntry::toFileEntry, "oracle append");
        final var at = Collections.binarySearch(resident, row.getValue());
        resident.add(-(at + 1), new PkIndexEntry(DB, COLL, row.getValue(), row.getPosition(), row.getLength(),
                row.getPage(), row.getVersion()));
    }

    private void assertSameAsOracle() throws IOException {
        assertArrayEquals(Files.readAllBytes(oracleFile.toPath()), Files.readAllBytes(indexFile.toPath()));
    }

    @Test
    public void rewriteFromEqualsLegacyRewriteRows() throws IOException {
        for (var op = 0; op < 1500; op++) {
            final var choice = random.nextInt(10);
            if (choice < 6) {
                update(pickResident());
            } else if (choice < 8 && resident.size() > 1) {
                delete(pickResident());
            } else {
                insert();
            }
            assertSameAsOracle();
        }
        assertEquals(resident.size(), store.readWholePkIndexFile(DB, COLL).size());
    }

    @Test
    public void unchangedFileIsRewrittenFromMemory() throws IOException {
        final var victim = resident.get(5);
        final var attributes = Files.getLastModifiedTime(indexFile.toPath());
        final var tampered = Files.readString(indexFile.toPath(), StandardCharsets.UTF_8).replace(victim.toFileEntry(),
                victim.toFileEntry().replace(victim.getValue(), victim.getValue().toUpperCase(Locale.ROOT)));
        Files.writeString(indexFile.toPath(), tampered, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(indexFile.toPath(), attributes);
        delete(resident.get(10));
        assertTrue(Files.readString(indexFile.toPath(), StandardCharsets.UTF_8).contains(victim.getValue()));
        assertSameAsOracle();
    }

    @Test
    public void staleKnownLengthFallsBackToParse() throws IOException {
        final var outOfBand = new PkIndexEntry(DB, COLL, "zz-out-of-band", 1L, 2L, 0L, 3L);
        Files.writeString(indexFile.toPath(), outOfBand.toFileEntry() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        Files.writeString(oracleFile.toPath(), outOfBand.toFileEntry() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        update(pickResident());
        assertSameAsOracle();
        assertTrue(Files.readString(indexFile.toPath(), StandardCharsets.UTF_8).contains("zz-out-of-band"));
    }

    @Test
    public void nullResidentRowsFallsBackToParse() throws IOException {
        final var victim = resident.get(7);
        final var modified = Files.getLastModifiedTime(indexFile.toPath());
        final var renamed = victim.toFileEntry().replace(victim.getValue(), victim.getValue().toUpperCase(Locale.ROOT));
        for (final var file : List.of(indexFile, oracleFile)) {
            Files.writeString(file.toPath(),
                    Files.readString(file.toPath(), StandardCharsets.UTF_8).replace(victim.toFileEntry(), renamed),
                    StandardCharsets.UTF_8);
        }
        Files.setLastModifiedTime(indexFile.toPath(), modified);
        final var old = resident.get(20);
        store.deleteIndexValue(old, null);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, old.getValue(), null);
        assertSameAsOracle();
        assertTrue(Files.readString(indexFile.toPath(), StandardCharsets.UTF_8).contains(renamed));
    }

    @Test
    public void residentRowsThatMissedAWriteFallBackToParse() throws IOException {
        final var missed = resident.get(3);
        store.deleteIndexValue(missed, null);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, missed.getValue(), null);
        final var stale = new ArrayList<>(resident);
        final var old = resident.get(40);
        final var position = old.getPosition() + old.getLength();
        store.updateIndexValues(DB, COLL, old.getValue(), position, 99, old.getPage(), 5L, stale);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, old.getValue(),
                new PkIndexEntry(DB, COLL, old.getValue(), position, 99, old.getPage(), 5L));
        assertSameAsOracle();
        assertFalse(store.readWholePkIndexFile(DB, COLL).stream()
                .anyMatch(row -> row.getValue().equals(missed.getValue())));
    }

    @Test
    public void unsortedResidentRowsGiveTheSameFile() throws IOException {
        final var shuffled = new ArrayList<>(resident);
        Collections.shuffle(shuffled, random);
        final var old = resident.get(60);
        store.deleteIndexValue(old, shuffled);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, old.getValue(), null);
        assertSameAsOracle();
    }

    @Test
    public void unrecognisedPkIdxStillRefusesOnFallback() throws IOException {
        final var garbage = "not a pk index\nat all\n".getBytes(StandardCharsets.UTF_8);
        Files.write(indexFile.toPath(), garbage);
        final var old = resident.get(1);
        final var failure = assertThrows(IOException.class, () -> store.deleteIndexValue(old, resident));
        assertTrue(failure.getMessage().contains("refusing to rewrite"));
        assertArrayEquals(garbage, Files.readAllBytes(indexFile.toPath()));
    }

    @Test
    public void residentEntriesAreNotMutatedByTheRewrite() throws IOException {
        final var before = resident.stream().map(PkIndexEntry::toFileEntry).toList();
        final var old = resident.getFirst();
        final var samePage = resident.stream().filter(row -> row.getPage() == old.getPage()).count();
        assertTrue(samePage > 1);
        store.updateIndexValues(DB, COLL, old.getValue(), old.getPosition() + old.getLength() + 10, 50, old.getPage(),
                9L, resident);
        assertEquals(before, resident.stream().map(PkIndexEntry::toFileEntry).toList());
    }

    @Test
    public void missingIdUsesTheParsePath() throws IOException {
        final var ghost = new PkIndexEntry(DB, COLL, "not-indexed", 0L, 10L, 0L, 1L);
        store.deleteIndexValue(ghost, resident);
        LegacyStorageOracle.updatePkIndex(oracleFile, DB, COLL, ghost.getValue(), null);
        assertSameAsOracle();
    }
}
