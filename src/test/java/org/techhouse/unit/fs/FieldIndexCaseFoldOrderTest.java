package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.CaseFolding;
import org.techhouse.utils.SearchUtils;

public class FieldIndexCaseFoldOrderTest {
    private static final String FIELD = "folded";
    private static final String EMOJI = "😀";
    private static final String FULLWIDTH_A = "Ａ";
    private static final List<String> VALUES = List.of(EMOJI, FULLWIDTH_A, "aa" + FULLWIDTH_A, "\ud83d" + EMOJI);

    private FileSystem fileSystem;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "filePath", TestGlobals.PATH);
        fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        fileSystem.createBaseDbPath();
        fileSystem.createAdminDatabase();
        fileSystem.createDatabaseFolder(TestGlobals.DB);
        fileSystem.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private static File indexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + FIELD + "-String.idx");
    }

    private static String idOf(String value) {
        return "id" + VALUES.indexOf(value);
    }

    private static List<List<String>> permutations(List<String> values) {
        if (values.size() <= 1) {
            return List.of(values);
        }
        final var result = new ArrayList<List<String>>();
        for (final var head : values) {
            final var rest = new ArrayList<>(values);
            rest.remove(head);
            for (final var tail : permutations(rest)) {
                final var permutation = new ArrayList<String>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }

    private List<FieldIndexEntry<String>> indexWrittenInOrder(List<String> order) throws IOException {
        Files.deleteIfExists(indexFile().toPath());
        for (final var value : order) {
            fileSystem.updateIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                    new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, value, new HashSet<>(Set.of(idOf(value)))),
                    null);
        }
        return fileSystem.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
    }

    private static Set<String> scanEquals(String operand) {
        return VALUES.stream().filter(value -> CaseFolding.equal(operand, value)).map(FieldIndexCaseFoldOrderTest::idOf)
                .collect(Collectors.toSet());
    }

    private static Set<String> scanNotEquals(String operand) {
        final var all = VALUES.stream().map(FieldIndexCaseFoldOrderTest::idOf).collect(Collectors.toSet());
        all.removeAll(scanEquals(operand));
        return all;
    }

    @Test
    public void test_equals_finds_every_value_in_every_file_order() throws IOException {
        for (final var order : permutations(VALUES)) {
            final var index = indexWrittenInOrder(order);
            for (final var operand : VALUES) {
                assertEquals(scanEquals(operand),
                        SearchUtils.findingByOperator(index, FieldOperatorType.EQUALS, operand),
                        "EQUALS " + operand + " over " + order);
                assertEquals(scanNotEquals(operand),
                        SearchUtils.findingByOperator(index, FieldOperatorType.NOT_EQUALS, operand),
                        "NOT_EQUALS " + operand + " over " + order);
            }
        }
    }

    @Test
    public void test_membership_finds_every_value_in_every_file_order() throws IOException {
        for (final var order : permutations(VALUES)) {
            final var index = indexWrittenInOrder(order);
            for (final var operand : VALUES) {
                assertEquals(scanEquals(operand),
                        SearchUtils.findingInNotIn(index, FieldOperatorType.IN, List.of(operand)),
                        "IN " + operand + " over " + order);
            }
        }
    }

    @Test
    public void test_a_32_entry_surrogate_heavy_index_sorts_without_a_contract_violation() {
        final var entries = new ArrayList<FieldIndexEntry<String>>();
        final var pieces = List.of(EMOJI, FULLWIDTH_A, "\ud83d", "a", "\ude00", "B");
        for (var i = 0; i < 64; i++) {
            final var value = pieces.get(i % pieces.size()) + pieces.get((i / pieces.size()) % pieces.size())
                    + pieces.get((i * 7) % pieces.size());
            entries.add(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, value, Set.of("id" + i)));
        }
        assertDoesNotThrow(() -> entries.sort((a, b) -> a.compareTo(b.getValue())));
        for (var i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).compareTo(entries.get(i).getValue()) <= 0);
        }
    }
}
