package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.fs.FieldIndexLoader;
import org.techhouse.fs.IndexFileLookup;
import org.techhouse.fs.IndexFileLookup.Bound;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.test.TestGlobals;
import org.techhouse.utils.SearchUtils;

public class IndexFileLookupTest extends FileSystemTestBase {
    private static final String FIELD = "looked";
    private static final String US = Globals.ID_SEPARATOR;

    @BeforeAll
    static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    private static File indexFile(Class<?> type) {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-" + FIELD + "-"
                + IndexKind.fileLabel(type) + Globals.INDEX_FILE_EXTENSION);
    }

    private static File logFile(Class<?> type) {
        return new File(indexFile(type).getPath() + Globals.INDEX_LOG_SUFFIX);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void writeIndex(Class<T> type, List<FieldIndexEntry<T>> entries) {
        final var sorted = new ArrayList<>(entries);
        sorted.sort(FieldIndexLoader.orderFor(type));
        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, FIELD, Map.of(type, (List) sorted));
    }

    private static <T> FieldIndexEntry<T> entry(T value, String... ids) {
        return new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, value, new HashSet<>(List.of(ids)));
    }

    private static void writeLines(File file, String... lines) throws IOException {
        final var text = new StringBuilder();
        for (final var line : lines) {
            text.append(line).append('\n');
        }
        Files.writeString(file.toPath(), text.toString(), StandardCharsets.UTF_8);
    }

    private <T> List<FieldIndexEntry<T>> fullLoad(Class<T> type) throws IOException {
        return fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, type);
    }

    private static long idCount(List<? extends FieldIndexEntry<?>> entries) {
        return entries.stream().mapToLong(entry -> entry.getIds().size()).sum();
    }

    private <T> void assertEqualsAgreesWithFullLoad(Class<T> type, List<T> operands) throws IOException {
        final var loaded = fullLoad(type);
        for (final var operand : operands) {
            assertEquals(SearchUtils.findingByOperator(loaded, FieldOperatorType.EQUALS, operand),
                    IndexFileLookup.equalsIds(indexFile(type), type, operand), "EQUALS " + operand);
            assertEquals(SearchUtils.findingByOperator(loaded, FieldOperatorType.NOT_EQUALS, operand),
                    IndexFileLookup.complementIds(indexFile(type), type, List.of(operand)), "NOT_EQUALS " + operand);
        }
        assertEquals(SearchUtils.findingInNotIn(loaded, FieldOperatorType.IN, operands),
                IndexFileLookup.anyOfIds(indexFile(type), type, operands), "IN " + operands);
        assertEquals(SearchUtils.countAnyOf(loaded, operands),
                IndexFileLookup.anyOfCount(indexFile(type), type, operands), "IN count " + operands);
        assertEquals(idCount(loaded), IndexFileLookup.idCount(indexFile(type), type));
    }

    @Test
    public void equalsFindsTheLineByPositionedSearch() throws IOException {
        final var strings = new ArrayList<FieldIndexEntry<String>>();
        for (var i = 0; i < 3000; i++) {
            strings.add(entry("value" + i, "s" + i, "t" + i));
        }
        strings.add(entry("apple", "a1"));
        strings.add(entry("Apple", "a2"));
        strings.add(entry("APPLE", "a3", "a4"));
        strings.add(entry("banana", "b1"));
        writeIndex(String.class, strings);
        assertEqualsAgreesWithFullLoad(String.class, List.of("apple", "APPLE", "aPpLe", "banana", "BANANA", "value0",
                "VALUE2999", "value1500", "absent", "zzz", "0"));
        assertEquals(Set.of("a1", "a2", "a3", "a4"),
                IndexFileLookup.equalsIds(indexFile(String.class), String.class, "ApPlE"));

        final var numbers = new ArrayList<FieldIndexEntry<Number>>();
        for (var i = -1000; i < 2000; i++) {
            numbers.add(entry(i * 1.5, "n" + i));
        }
        writeIndex(Number.class, numbers);
        assertEqualsAgreesWithFullLoad(Number.class, List.of(0, -0.0, 1.5, 3, -1500.0, 2998.5, 2.25, 1e9));
        assertEquals(Set.of("n2"), IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 3));

        writeIndex(Boolean.class, List.of(entry(true, "y1", "y2"), entry(false, "f1")));
        assertEqualsAgreesWithFullLoad(Boolean.class, List.of(true, false));

        final var dates = new ArrayList<FieldIndexEntry<JsonDateTime>>();
        for (var day = 1; day <= 28; day++) {
            dates.add(entry(dateTime(day), "d" + day));
        }
        writeIndex(JsonDateTime.class, dates);
        assertEqualsAgreesWithFullLoad(JsonDateTime.class, List.of(dateTime(1), dateTime(14), dateTime(28)));
        assertEquals(Set.of("d7"),
                IndexFileLookup.equalsIds(indexFile(JsonDateTime.class), JsonDateTime.class, dateTime(7)));
    }

    private static JsonDateTime dateTime(int day) {
        return new JsonDateTime("#datetime(2024-02-" + (day < 10 ? "0" + day : day) + "T10:30:00)");
    }

    @Test
    public void rangeReturnsOnlyMatchingLines() throws IOException {
        final var numbers = new ArrayList<FieldIndexEntry<Number>>();
        for (var i = 0; i < 5000; i++) {
            numbers.add(entry((double) i, "n" + i));
        }
        writeIndex(Number.class, numbers);
        final var loaded = fullLoad(Number.class);
        for (final var type : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            for (final var limit : List.of(-1, 0, 17, 2500.5, 4999, 5000)) {
                final var lower = type == FieldOperatorType.GREATER_THAN
                        || type == FieldOperatorType.GREATER_THAN_EQUALS;
                final var inclusive = type == FieldOperatorType.GREATER_THAN_EQUALS
                        || type == FieldOperatorType.SMALLER_THAN_EQUALS;
                final var expected = SearchUtils.findingByOperator(loaded, type, limit);
                final var bounds = List.of(new Bound(limit, lower, inclusive));
                assertEquals(expected, IndexFileLookup.rangeIds(indexFile(Number.class), Number.class, bounds),
                        type + " " + limit);
                assertEquals(expected.size(),
                        IndexFileLookup.rangeCount(indexFile(Number.class), Number.class, bounds));
            }
        }
        final var between = IndexFileLookup.rangeIds(indexFile(Number.class), Number.class,
                List.of(new Bound(10, true, false), new Bound(15, false, true)));
        assertEquals(Set.of("n11", "n12", "n13", "n14", "n15"), between);
        assertEquals(Set.of(), IndexFileLookup.rangeIds(indexFile(Number.class), Number.class,
                List.of(new Bound(15, true, false), new Bound(10, false, false))));

        final var dates = new ArrayList<FieldIndexEntry<JsonDateTime>>();
        for (var day = 1; day <= 28; day++) {
            dates.add(entry(dateTime(day), "d" + day));
        }
        writeIndex(JsonDateTime.class, dates);
        assertEquals(Set.of("d26", "d27", "d28"), IndexFileLookup.rangeIds(indexFile(JsonDateTime.class),
                JsonDateTime.class, List.of(new Bound(dateTime(25), true, false))));
        assertNull(
                IndexFileLookup.rangeIds(indexFile(String.class), String.class, List.of(new Bound("a", true, false))),
                "a string range declines");
    }

    @Test
    public void logOverlayAddsAndRemovesIds() throws IOException {
        writeIndex(String.class, List.of(entry("alpha", "a1", "a2"), entry("beta", "b1"), entry("gamma", "g1")));
        writeLines(logFile(String.class), "+" + US + "alpha" + US + "a3", "-" + US + "alpha" + US + "a1",
                "-" + US + "beta" + US + "b1", "+" + US + "Alpha" + US + "x1", "+" + US + "delta" + US + "d1",
                "-" + US + "delta" + US + "d1", "+" + US + "delta" + US + "d2", "+" + US + "beta" + US + "b2");
        final var loaded = fullLoad(String.class);
        assertEquals(Set.of("a2", "a3", "x1"),
                IndexFileLookup.equalsIds(indexFile(String.class), String.class, "ALPHA"));
        assertEquals(Set.of("b2"), IndexFileLookup.equalsIds(indexFile(String.class), String.class, "beta"));
        assertEquals(Set.of("d2"), IndexFileLookup.equalsIds(indexFile(String.class), String.class, "delta"));
        assertEqualsAgreesWithFullLoad(String.class, List.of("alpha", "beta", "gamma", "delta", "epsilon"));
        assertEquals(6, idCount(loaded));

        Files.delete(indexFile(String.class).toPath());
        assertEquals(Set.of("d2"), IndexFileLookup.equalsIds(indexFile(String.class), String.class, "delta"),
                "a type that only has a log still answers from it");
        assertEquals(idCount(fullLoad(String.class)), IndexFileLookup.idCount(indexFile(String.class), String.class));
    }

    @Test
    public void malformedLineOrUnsortedNeighbourDeclines() throws IOException {
        writeLines(indexFile(String.class), "a" + US + "1", "b" + US + "2", "B" + US + "3", "c" + US + "4");
        assertNull(IndexFileLookup.equalsIds(indexFile(String.class), String.class, "b"),
                "a case-folded tie written out of raw order");
        writeLines(indexFile(Number.class), "1" + US + "a", "3" + US + "c", "2" + US + "b");
        assertNull(IndexFileLookup.complementIds(indexFile(Number.class), Number.class, List.of(1)),
                "a complement reads every line, so any unsorted neighbour declines it");

        writeLines(indexFile(Number.class), "1" + US + "a", "no-separator", "5" + US + "e");
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 5));
        assertEquals(IndexFileLookup.DECLINED, IndexFileLookup.idCount(indexFile(Number.class), Number.class));

        writeLines(indexFile(Number.class), "1" + US + "a", "not-a-number" + US + "x", "5" + US + "e");
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 5));

        writeLines(indexFile(Number.class), "1" + US + "a", "1" + US + "b");
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 1), "a duplicated key declines");

        writeLines(indexFile(Number.class), "1" + US + "a", "5" + US + "e");
        writeLines(logFile(Number.class), "*" + US + "5" + US + "z");
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 5), "a log line without a sign");
        Files.writeString(logFile(Number.class).toPath(), "+" + US + "5" + US + "z", StandardCharsets.UTF_8);
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 5), "a torn log tail");
        Files.delete(logFile(Number.class).toPath());
        assertEquals(Set.of("e"), IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 5));
    }

    @Test
    public void candidateOnlyTypesNeverAnswerFromTheFile() throws IOException {
        assertFalse(IndexFileLookup.supports(JsonVector.class));
        assertFalse(IndexFileLookup.supports(JsonGeo.class));
        assertEquals(IndexFileLookup.DECLINED, IndexFileLookup.idCount(indexFile(JsonVector.class), JsonVector.class));
        assertNull(IndexFileLookup.equalsIds(indexFile(JsonGeo.class), JsonGeo.class, new JsonGeo("#geo(1,2)")));
    }

    @Test
    public void missingFileDeclinesNotEmpty() throws IOException {
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 1));
        assertNull(IndexFileLookup.rangeIds(indexFile(Number.class), Number.class, List.of(new Bound(1, true, true))));
        assertNull(IndexFileLookup.complementIds(indexFile(Number.class), Number.class, List.of(1)));
        assertEquals(IndexFileLookup.DECLINED, IndexFileLookup.idCount(indexFile(Number.class), Number.class));
        assertEquals(IndexFileLookup.DECLINED,
                IndexFileLookup.anyOfCount(indexFile(Number.class), Number.class, List.of(1)));
        assertEquals(IndexFileLookup.DECLINED,
                IndexFileLookup.rangeCount(indexFile(String.class), String.class, List.of(new Bound("a", true, true))));
        writeIndex(Number.class, List.of(entry(1.0, "a")));
        assertEquals(Set.of(), IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, 2));
        assertNotNull(fs.indexFiles().equalsIds(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class, List.of(1)));
        assertNull(IndexFileLookup.equalsIds(indexFile(Number.class), Number.class, "a string operand"));
        assertNull(fs.indexFiles().equalsIds(TestGlobals.DB, TestGlobals.COLL, Globals.PK_FIELD, Number.class,
                List.of(1)));
    }
}
