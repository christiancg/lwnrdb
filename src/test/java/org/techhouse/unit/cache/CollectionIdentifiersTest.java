package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class CollectionIdentifiersTest {
    private static final String DB = "idMemoDb";
    private static final String CAP_DB = "idMemoCapDb";
    private static final String THREADS_DB = "idMemoThreadsDb";
    private static final int PAST_THE_CAP = 9_000;
    private static final int THREADS = 8;
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        cache.evictDatabase(DB);
        cache.evictDatabase(CAP_DB);
        cache.evictDatabase(THREADS_DB);
        TestUtils.standardTearDown();
    }

    private static String concatenated(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    @Test
    public void the_identifier_equals_the_concatenation_and_is_remembered() {
        final var first = Cache.getCollectionIdentifier(DB, "orders");
        final var again = Cache.getCollectionIdentifier(DB, "orders");

        assertEquals(concatenated(DB, "orders"), first);
        assertSame(first, again);
        assertEquals(concatenated(DB, "items"), Cache.getCollectionIdentifier(DB, "items"));
    }

    @Test
    public void canonical_names_are_the_first_seen_instances() {
        final var db = new String(DB.toCharArray());
        final var coll = new String("names".toCharArray());
        final var first = Cache.canonicalNames(db, coll);

        final var again = Cache.canonicalNames(new String(DB.toCharArray()), new String("names".toCharArray()));

        assertSame(db, again.dbName());
        assertSame(coll, again.collName());
        assertSame(first.identifier(), Cache.getCollectionIdentifier(DB, "names"));
    }

    @Test
    public void evicting_a_collection_forgets_its_canonical_names() {
        final var coll = new String("forgotten".toCharArray());
        Cache.canonicalNames(DB, coll);

        cache.evictCollection(DB, "forgotten");

        assertNotSame(coll, Cache.canonicalNames(DB, new String("forgotten".toCharArray())).collName());
    }

    @Test
    public void past_the_cap_the_memo_starts_over_and_keeps_canonicalising() {
        final var early = Cache.getCollectionIdentifier(CAP_DB, "c0");
        for (var i = 1; i < PAST_THE_CAP; i++) {
            Cache.getCollectionIdentifier(CAP_DB, "c" + i);
        }
        final var coll = new String("late".toCharArray());

        final var first = Cache.canonicalNames(CAP_DB, coll);
        final var again = Cache.canonicalNames(CAP_DB, new String("late".toCharArray()));

        assertSame(coll, again.collName(), "a name first seen after the memo filled is still canonicalised");
        assertSame(first.identifier(), again.identifier());
        assertNotSame(early, Cache.getCollectionIdentifier(CAP_DB, "c0"),
                "a name remembered before the memo filled ages out with that generation");
        assertEquals(early, Cache.getCollectionIdentifier(CAP_DB, "c0"));
    }

    @Test
    public void a_dead_name_left_by_a_late_lookup_ages_out_instead_of_filling_the_memo_for_good() {
        for (var round = 0; round < 3; round++) {
            for (var i = 0; i < PAST_THE_CAP; i++) {
                final var dropped = "dead" + round + "_" + i;
                Cache.getCollectionIdentifier(CAP_DB, dropped);
                cache.evictCollection(CAP_DB, dropped);
                Cache.getCollectionIdentifier(CAP_DB, dropped);
            }
        }
        final var live = new String("live".toCharArray());
        Cache.canonicalNames(CAP_DB, live);

        assertSame(live, Cache.canonicalNames(CAP_DB, new String("live".toCharArray())).collName());
    }

    @Test
    public void a_null_name_keeps_the_concatenation_behaviour() {
        assertEquals(concatenated(null, "orders"), Cache.getCollectionIdentifier(null, "orders"));
        assertEquals(concatenated(DB, null), Cache.getCollectionIdentifier(DB, null));
        assertEquals(concatenated(null, null), Cache.getCollectionIdentifier(null, null));
    }

    @Test
    public void evicting_a_collection_forgets_only_its_identifier() {
        final var orders = Cache.getCollectionIdentifier(DB, "orders");
        final var items = Cache.getCollectionIdentifier(DB, "items");

        cache.evictCollection(DB, "orders");

        assertNotSame(orders, Cache.getCollectionIdentifier(DB, "orders"));
        assertEquals(orders, Cache.getCollectionIdentifier(DB, "orders"));
        assertSame(items, Cache.getCollectionIdentifier(DB, "items"));
    }

    @Test
    public void evicting_a_database_forgets_every_identifier_in_it() {
        final var orders = Cache.getCollectionIdentifier(DB, "orders");
        final var items = Cache.getCollectionIdentifier(DB, "items");

        cache.evictDatabase(DB);

        assertNotSame(orders, Cache.getCollectionIdentifier(DB, "orders"));
        assertNotSame(items, Cache.getCollectionIdentifier(DB, "items"));
    }

    @Test
    public void past_the_cap_identifiers_are_still_correct_and_the_latest_ones_are_remembered() {
        String last = null;
        for (var i = 0; i < PAST_THE_CAP; i++) {
            last = Cache.getCollectionIdentifier(CAP_DB, "c" + i);
            assertEquals(concatenated(CAP_DB, "c" + i), last);
        }

        assertSame(last, Cache.getCollectionIdentifier(CAP_DB, "c" + (PAST_THE_CAP - 1)));

        cache.evictDatabase(CAP_DB);
        final var room = Cache.getCollectionIdentifier(CAP_DB, "fresh");
        assertSame(room, Cache.getCollectionIdentifier(CAP_DB, "fresh"));
    }

    @Test
    public void concurrent_callers_always_get_the_right_identifier() throws Exception {
        final var wrong = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            final var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var thread = 0; thread < THREADS; thread++) {
                tasks.add(pool.submit(() -> {
                    for (var round = 0; round < 5_000; round++) {
                        final var collection = "c" + round % 100;
                        final var identifier = Cache.getCollectionIdentifier(THREADS_DB, collection);
                        if (!identifier.equals(concatenated(THREADS_DB, collection))) {
                            wrong.add(identifier);
                        }
                    }
                }));
            }
            for (final var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(0, wrong.size(), wrong.toString());
    }
}
