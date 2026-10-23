package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ex.InvalidPathSegmentException;

public class FilePathsMemoTest {
    private static final String ROOT = "root";
    private static final int THREADS = 8;
    private final FilePaths paths = new FilePaths();

    @BeforeEach
    public void setUp() {
        paths.useDbPath(ROOT);
    }

    private static String expectedPage(String root, String physicalDatabase, String collection, long page) {
        return new File(root + Globals.FILE_SEPARATOR + physicalDatabase + Globals.FILE_SEPARATOR + collection
                + Globals.FILE_SEPARATOR + collection + Globals.FILE_PAGE_SEPARATOR + page + Globals.DB_FILE_EXTENSION)
                .getPath();
    }

    @Test
    public void a_remembered_prefix_gives_the_same_page_paths_as_the_first_computation() {
        final var first = paths.collectionPage("shop", "orders", 3);
        final var again = paths.collectionPage("shop", "orders", 3);
        final var otherPage = paths.collectionPage("shop", "orders", 4);

        assertEquals(expectedPage(ROOT, "shop", "orders", 3), first.getPath());
        assertEquals(first, again);
        assertEquals(expectedPage(ROOT, "shop", "orders", 4), otherPage.getPath());
        assertEquals(1, paths.rememberedPrefixCount());
    }

    @Test
    public void every_builder_that_shares_the_prefix_agrees_with_a_fresh_instance() {
        final var fresh = new FilePaths();
        fresh.useDbPath(ROOT);
        paths.collectionPage("shop", "orders", 0);

        assertEquals(fresh.writeAheadLog("shop", "orders"), paths.writeAheadLog("shop", "orders"));
        assertEquals(fresh.intentLog("shop", "orders"), paths.intentLog("shop", "orders"));
        assertEquals(fresh.vacuumFile("shop", "orders", 2), paths.vacuumFile("shop", "orders", 2));
        assertEquals(fresh.pkIndexFile("shop", "orders"), paths.pkIndexFile("shop", "orders"));
        assertEquals(fresh.tombstoneFile("shop", "orders"), paths.tombstoneFile("shop", "orders"));
        assertEquals(fresh.schemaFile("shop", "orders"), paths.schemaFile("shop", "orders"));
        assertEquals(fresh.triggersFile("shop", "orders"), paths.triggersFile("shop", "orders"));
        assertEquals(fresh.indexFile("shop", "orders", "price", "Number"),
                paths.indexFile("shop", "orders", "price", "Number"));
    }

    @Test
    public void the_admin_pages_database_keeps_its_physical_translation() {
        final var physical = Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR + Globals.ADMIN_PAGES_FOLDER;

        final var first = paths.collectionPage(Globals.ADMIN_PAGES_DB_NAME, "shop_orders", 0);
        final var again = paths.collectionPage(Globals.ADMIN_PAGES_DB_NAME, "shop_orders", 0);

        assertEquals(expectedPage(ROOT, physical, "shop_orders", 0), first.getPath());
        assertEquals(first, again);
    }

    @Test
    public void a_refused_name_is_never_remembered() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage("shop", "..", 1));
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage("a/b", "orders", 1));
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage("shop", "a\\b", 1));
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage("shop", "", 1));
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage(null, "orders", 1));
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage("shop", null, 1));
        assertEquals(0, paths.rememberedPrefixCount());
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionPage("shop", "..", 1));
    }

    @Test
    public void changing_the_base_path_forgets_every_prefix() {
        paths.collectionPage("shop", "orders", 0);

        paths.useDbPath("elsewhere");

        assertEquals(0, paths.rememberedPrefixCount());
        assertEquals(expectedPage("elsewhere", "shop", "orders", 0),
                paths.collectionPage("shop", "orders", 0).getPath());
        assertEquals(1, paths.rememberedPrefixCount());
    }

    @Test
    public void forgetting_a_collection_or_a_database_releases_its_prefixes_and_rebuilds_them_on_use() {
        paths.collectionPage("shop", "orders", 0);
        paths.collectionPage("shop", "items", 0);
        paths.collectionPage("other", "orders", 0);

        paths.forgetCollection("shop", "orders");
        assertEquals(2, paths.rememberedPrefixCount());
        paths.forgetDatabase("shop");
        assertEquals(1, paths.rememberedPrefixCount());
        paths.forgetCollection("missing", "orders");
        paths.forgetDatabase("missing");

        assertEquals(1, paths.rememberedPrefixCount());
        assertEquals(expectedPage(ROOT, "shop", "orders", 7), paths.collectionPage("shop", "orders", 7).getPath());
        assertEquals(2, paths.rememberedPrefixCount());
    }

    @Test
    public void past_the_cap_paths_are_still_correct_but_no_longer_remembered() {
        for (var i = 0; i < FilePaths.MAX_REMEMBERED_PREFIXES + 50; i++) {
            final var collection = "c" + i;
            assertEquals(expectedPage(ROOT, "shop", collection, 1),
                    paths.collectionPage("shop", collection, 1).getPath());
        }

        assertEquals(FilePaths.MAX_REMEMBERED_PREFIXES, paths.rememberedPrefixCount());
        paths.forgetCollection("shop", "c0");
        assertEquals(FilePaths.MAX_REMEMBERED_PREFIXES - 1, paths.rememberedPrefixCount());
        paths.collectionPage("shop", "c" + (FilePaths.MAX_REMEMBERED_PREFIXES + 10), 1);
        assertEquals(FilePaths.MAX_REMEMBERED_PREFIXES, paths.rememberedPrefixCount());
    }

    @Test
    public void concurrent_callers_get_the_same_paths_and_each_prefix_is_counted_once() throws Exception {
        final var failures = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            final var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var thread = 0; thread < THREADS; thread++) {
                tasks.add(pool.submit(() -> {
                    for (var round = 0; round < 2_000; round++) {
                        final var collection = "c" + round % 100;
                        final var path = paths.collectionPage("shop", collection, round).getPath();
                        if (!path.equals(expectedPage(ROOT, "shop", collection, round))) {
                            failures.add(path);
                        }
                    }
                }));
            }
            for (final var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(0, failures.size(), failures.toString());
        assertEquals(100, paths.rememberedPrefixCount());
    }
}
