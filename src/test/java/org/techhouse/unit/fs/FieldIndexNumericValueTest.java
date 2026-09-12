package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexNumericValueTest {
    private static final String FIELD = "bucket";

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        File dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && dbDir.canRead() && dbDir.canWrite()
                && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private static FileSystem fs() throws NoSuchFieldException, IllegalAccessException {
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        return fileSystem;
    }

    private static List<FieldIndexEntry<Number>> read(FileSystem fileSystem) throws IOException {
        return fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);
    }

    @Test
    public void test_integer_and_double_spellings_of_one_value_share_an_entry() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 7, Set.of("id1")), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 7.0d, Set.of("id1", "id2")), null);

        final var index = read(fileSystem);
        assertEquals(1, index.size(), "an integral value must occupy a single index entry");
        assertEquals(Set.of("id1", "id2"), index.getFirst().getIds());
    }

    @Test
    public void test_integral_value_written_as_double_keeps_the_canonical_key() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 3.0d, Set.of("a")), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 3, Set.of("a", "b")), null);

        final var index = read(fileSystem);
        assertEquals(1, index.size(), "3 and 3.0 must not produce two lines");
        assertEquals(Set.of("a", "b"), index.getFirst().getIds());
    }

    @Test
    public void test_value_above_integer_max_round_trips_on_one_line() throws Exception {
        final var fileSystem = fs();
        final var big = 10_000_000_000d;

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, big, Set.of("x")), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, big, Set.of("x", "y")), null);

        final var index = read(fileSystem);
        assertEquals(1, index.size(), "a large integral value must occupy a single index entry");
        assertEquals(big, index.getFirst().getValue().doubleValue());
        assertEquals(Set.of("x", "y"), index.getFirst().getIds());
    }

    @Test
    public void test_non_integral_values_are_unaffected() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 0.5d, Set.of("p")), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 0.5d, Set.of("p", "q")), null);

        final var index = read(fileSystem);
        assertEquals(1, index.size());
        assertEquals(0.5d, index.getFirst().getValue().doubleValue());
        assertEquals(Set.of("p", "q"), index.getFirst().getIds());
    }

    @Test
    public void test_distinct_values_stay_on_separate_entries() throws Exception {
        final var fileSystem = fs();

        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 1, Set.of("a")), null);
        fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 2, Set.of("b")), null);

        final var index = read(fileSystem);
        assertEquals(2, index.size());
    }
}
