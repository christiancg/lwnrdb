package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.data.NameMemo;

public class NameMemoTest {
    private static final int CAPACITY = 4;

    @Test
    public void the_first_remembered_value_wins() {
        final var memo = new NameMemo<String>(CAPACITY);

        final var first = memo.remember("db", "coll", "first");
        final var second = memo.remember("db", "coll", "second");

        assertSame(first, second);
        assertSame(first, memo.get("db", "coll"));
        assertEquals(1, memo.size());
    }

    @Test
    public void a_full_memo_starts_a_new_generation_holding_only_the_newest_name() {
        final var memo = new NameMemo<String>(CAPACITY);
        for (var i = 0; i < CAPACITY; i++) {
            memo.remember("db", "c" + i, "v" + i);
        }

        assertEquals("late", memo.remember("db", "late", "late"));

        assertEquals(1, memo.size());
        assertNull(memo.get("db", "c0"));
        assertEquals("late", memo.get("db", "late"));
    }

    @Test
    public void forgetting_a_name_of_a_dropped_generation_leaves_the_count_alone() {
        final var memo = new NameMemo<String>(CAPACITY);
        for (var i = 0; i <= CAPACITY; i++) {
            memo.remember("db", "c" + i, "v" + i);
        }

        memo.forget("db", "c0");
        memo.forgetAll("other");

        assertEquals(1, memo.size());
        memo.forgetAll("db");
        assertEquals(0, memo.size());
    }

    @Test
    public void null_names_are_neither_found_nor_forgotten() {
        final var memo = new NameMemo<String>(CAPACITY);

        assertNull(memo.get(null, "coll"));
        assertNull(memo.get("db", null));
        memo.forget(null, "coll");
        memo.forget("db", null);
        memo.forgetAll(null);

        assertEquals(0, memo.size());
    }

    @Test
    public void clear_forgets_everything() {
        final var memo = new NameMemo<String>(CAPACITY);
        memo.remember("db", "coll", "v");

        memo.clear();

        assertEquals(0, memo.size());
        assertNull(memo.get("db", "coll"));
    }

    @Test
    public void concurrent_callers_stay_within_one_generation_of_the_capacity() throws Exception {
        final var memo = new NameMemo<String>(64);
        final var wrong = new ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            final var tasks = new ArrayList<Future<?>>();
            for (var thread = 0; thread < 8; thread++) {
                tasks.add(pool.submit(() -> {
                    for (var round = 0; round < 5_000; round++) {
                        final var name = "c" + round % 500;
                        final var value = memo.remember("db", name, name);
                        if (!value.equals(name)) {
                            wrong.add(value);
                        }
                    }
                }));
            }
            for (final var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(0, wrong.size(), wrong.toString());
        assertTrue(memo.size() <= 64 + 8, "size " + memo.size());
    }
}
