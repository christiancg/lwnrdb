package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.ChunkedList;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.fs.FieldIndexLoader;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.SearchUtils;

public class SearchUtilsMembershipRunsTest {
    private static final int VALUES = 1_500;
    private static final int TRIALS = 40;

    private static <T> List<FieldIndexEntry<T>> sortedEntries(Class<T> type, IntFunction<T> valueOf) {
        final var entries = new ArrayList<FieldIndexEntry<T>>(VALUES);
        for (var i = 0; i < VALUES; i++) {
            final var ids = new HashSet<String>();
            for (var copy = 0; copy <= i % 3; copy++) {
                ids.add("id-" + i + "-" + copy);
            }
            entries.add(new FieldIndexEntry<>("db", "coll", valueOf.apply(i), ids));
        }
        entries.sort(FieldIndexLoader.orderFor(type));
        return entries;
    }

    private static List<FieldIndexEntry<String>> spellings() {
        return sortedEntries(String.class, i -> (i % 2 == 0 ? "K" : "k") + String.format("%05d", i / 2));
    }

    private static List<FieldIndexEntry<Number>> numbers() {
        return sortedEntries(Number.class, i -> i == 0 ? -0.0 : i * 0.5);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<FieldIndexEntry<JsonCustom<?>>> dates() {
        final Class<JsonCustom<?>> type = (Class) JsonDateTime.class;
        return sortedEntries(type, i -> new JsonDateTime(LocalDateTime.of(2024, 1, 1, 0, 0).plusMinutes(i * 2L)));
    }

    private static <T> List<int[]> referenceRuns(List<FieldIndexEntry<T>> entries, List<T> operands) {
        final var runs = new ArrayList<int[]>();
        final var starts = new HashSet<Integer>();
        for (final var operand : operands) {
            final var run = SearchUtils.windowOf(entries, FieldOperatorType.EQUALS, operand);
            if (run != null && starts.add(run[0])) {
                runs.add(run);
            }
        }
        runs.sort(Comparator.comparingInt(run -> run[0]));
        return runs;
    }

    private static List<List<Integer>> asLists(List<int[]> runs) {
        return runs.stream().map(run -> List.of(run[0], run[1])).toList();
    }

    private static <T> long referenceCount(List<FieldIndexEntry<T>> entries, List<T> operands) {
        final var ids = new HashSet<String>();
        for (final var run : referenceRuns(entries, operands)) {
            for (final var entry : entries.subList(run[0], run[1])) {
                ids.addAll(entry.getIds());
            }
        }
        return ids.size();
    }

    private static <T> void assertAgrees(List<FieldIndexEntry<T>> plain, List<T> operands, String label) {
        final var chunked = ChunkedList.copyOf(plain);
        final var expected = asLists(referenceRuns(plain, operands));
        assertEquals(expected, asLists(SearchUtils.membershipRuns(plain, operands)), label);
        assertEquals(expected, asLists(SearchUtils.membershipRuns(chunked, operands)), label);
        final var count = referenceCount(plain, operands);
        assertEquals(count, SearchUtils.countAnyOf(plain, operands), label);
        assertEquals(count, SearchUtils.countAnyOf(chunked, operands), label);
        final var complement = new HashSet<String>();
        plain.forEach(entry -> complement.addAll(entry.getIds()));
        complement.removeAll(SearchUtils.findingInNotIn(plain, FieldOperatorType.IN, operands));
        assertEquals(complement, SearchUtils.findingInNotIn(chunked, FieldOperatorType.NOT_IN, operands), label);
    }

    private static <T> List<T> operandMix(Random random, List<T> present, List<T> absent, int size) {
        final var operands = new ArrayList<T>(size);
        for (var i = 0; i < size; i++) {
            final var pool = random.nextInt(4) == 0 ? absent : present;
            operands.add(pool.get(random.nextInt(pool.size())));
        }
        return operands;
    }

    private static <T> void assertAgreesForEveryShape(List<FieldIndexEntry<T>> entries, List<T> present,
            List<T> absent) {
        final var random = new Random(entries.size() * 31L + present.size());
        for (var trial = 0; trial < TRIALS; trial++) {
            final var size = trial < TRIALS / 2 ? 1 + random.nextInt(8) : 200 + random.nextInt(3 * VALUES);
            final var operands = operandMix(random, present, absent, size);
            assertAgrees(entries, operands, "unsorted " + size);
            final var sorted = new ArrayList<>(operands);
            sorted.sort(Comparator.comparing(String::valueOf));
            assertAgrees(entries, sorted, "sorted " + size);
            Collections.reverse(sorted);
            assertAgrees(entries, sorted, "reversed " + size);
        }
        assertAgrees(entries, List.of(), "no operands");
        assertAgrees(entries, absent, "only absent values");
        final var everyValueTwice = new ArrayList<>(present);
        everyValueTwice.addAll(present);
        assertAgrees(entries, everyValueTwice, "every value twice");
    }

    @Test
    public void test_case_folded_strings_give_the_runs_and_counts_of_one_search_per_operand() {
        final var entries = spellings();
        final var present = new ArrayList<String>();
        for (var i = 0; i < VALUES; i += 3) {
            present.add((i % 4 == 0 ? "K" : "k") + String.format("%05d", i / 2));
        }
        assertAgreesForEveryShape(entries, present, List.of("a", "K99999", "k007", "zzz", ""));
    }

    @Test
    public void test_numbers_give_the_runs_and_counts_of_one_search_per_operand() {
        final var entries = numbers();
        final var present = new ArrayList<Number>();
        for (var i = 0; i < VALUES; i += 2) {
            present.add(i == 0 ? 0.0 : i * 0.5);
        }
        present.add(-0.0);
        present.add(3);
        present.add(7L);
        assertAgreesForEveryShape(entries, present, List.of(-1.0, 0.25, 10_000.0, 1.5e300));
    }

    @Test
    public void test_custom_values_give_the_runs_and_counts_of_one_search_per_operand() {
        final var entries = dates();
        final var present = new ArrayList<JsonCustom<?>>();
        for (var i = 0; i < VALUES; i += 5) {
            present.add(new JsonDateTime(LocalDateTime.of(2024, 1, 1, 0, 0).plusMinutes(i * 2L)));
        }
        assertAgreesForEveryShape(entries, present,
                List.of(new JsonDateTime(LocalDateTime.of(2023, 1, 1, 0, 0)),
                        new JsonDateTime(LocalDateTime.of(2024, 1, 1, 0, 1)),
                        new JsonDateTime(LocalDateTime.of(2030, 1, 1, 0, 0))));
    }

    @Test
    public void test_booleans_and_one_entry_lists_agree() {
        final var booleans = new ArrayList<FieldIndexEntry<Boolean>>();
        booleans.add(new FieldIndexEntry<>("db", "coll", false, Set.of("f")));
        booleans.add(new FieldIndexEntry<>("db", "coll", true, Set.of("t1", "t2")));
        assertAgreesForEveryShape(booleans, List.of(true, false), List.of(true));
        final var single = List.of(new FieldIndexEntry<>("db", "coll", "Only", Set.of("o")));
        assertAgrees(single, List.of("only", "ONLY", "x"), "one entry");
        assertAgrees(List.of(), List.of("x", "y"), "empty index");
    }

    @Test
    public void test_a_long_case_variant_run_is_found_whole() {
        final var entries = new ArrayList<FieldIndexEntry<String>>();
        for (var mask = 0; mask < 256; mask++) {
            final var spelling = new StringBuilder();
            for (var bit = 0; bit < 8; bit++) {
                spelling.append((mask & (1 << bit)) == 0 ? 'q' : 'Q');
            }
            entries.add(new FieldIndexEntry<>("db", "coll", spelling.toString(), Set.of("v" + mask)));
        }
        entries.add(new FieldIndexEntry<>("db", "coll", "a", Set.of("a")));
        entries.add(new FieldIndexEntry<>("db", "coll", "z", Set.of("z")));
        entries.sort(FieldIndexLoader.orderFor(String.class));
        final var operands = new ArrayList<String>();
        for (var i = 0; i < 300; i++) {
            operands.add(i % 2 == 0 ? "qqqqqqqq" : "z");
        }
        assertAgrees(entries, operands, "long run");
        assertAgrees(entries, List.of("QQQQQQQQ"), "long run, one operand");
        final var run = SearchUtils.membershipRuns(entries, List.of("qQqQqQqQ")).getFirst();
        assertEquals(256, run[1] - run[0]);
    }
}
