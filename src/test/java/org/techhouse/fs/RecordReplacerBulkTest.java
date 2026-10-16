package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class RecordReplacerBulkTest {
    private static final String DB = "bulkDb";
    private static final String COLL = "bulkColl";

    @TempDir
    File tmp;

    private ReplacerFixture rf;
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final Configuration configuration = Configuration.getInstance();
    private long maxPageSize;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        rf.fx.seed(1, "x", "y");
        maxPageSize = configuration.getMaxPageSize();
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(configuration, "maxPageSize", maxPageSize);
    }

    private List<IndexedDbEntry> updates(String... ids) throws Exception {
        final var entries = new ArrayList<IndexedDbEntry>();
        for (final var id : ids) {
            entries.add(rf.indexed(id, "bulk-" + id));
        }
        return entries;
    }

    private int pkLines() throws Exception {
        return Files.readAllLines(rf.fx.paths.pkIndexFile(DB, COLL).toPath(), StandardCharsets.UTF_8).size();
    }

    @Test
    public void nUpdatesWriteOneIntentAndOnePkAppend() throws Exception {
        final var linesBefore = pkLines();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        faultInjector.arm(FaultPoint.PAGE_COPY_APPENDED, FaultAction.PAUSE, DB + "|" + COLL);
        final var bulk = CompletableFuture.supplyAsync(() -> {
            try {
                return rf.bulk(updates("a", "c", "y"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(faultInjector.awaitReached(FaultPoint.PAGE_COPY_APPENDED, 5_000));

        assertEquals(1, rf.intents().length, "one intent covers every item of the bulk");
        assertEquals(linesBefore, pkLines(), "nothing is committed until every copy is appended");
        faultInjector.release(FaultPoint.PAGE_COPY_APPENDED);
        final var result = bulk.get(5, TimeUnit.SECONDS);

        assertEquals(3, result.size());
        assertEquals(linesBefore + 3, pkLines());
        assertEquals(0, rf.intents().length);
        for (final var id : List.of("a", "c", "y")) {
            assertEquals(StorageFixture.record(id, "bulk-" + id), rf.fx.readAt(rf.fx.indexed(id)));
        }
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void theResultCarriesTheNewRowsAndThePreviousSizes() throws Exception {
        final var previous = rf.fx.indexed("b").getLength();

        final var result = rf.bulk(updates("b"));

        final var updated = result.getFirst();
        assertEquals(rf.fx.indexed("b").toFileEntry(), updated.getIndex().toFileEntry());
        assertEquals(previous, updated.getPreviousByteSize());
        assertEquals(StorageFixture.NEW_VERSION, updated.getVersion());
    }

    @Test
    public void aFailureBeforeTheAppendCutsBackEveryCopyAndCommitsNothing() throws Exception {
        final var page0 = Files.readAllBytes(rf.fx.page(0).toPath());
        final var linesBefore = pkLines();
        final var page1 = rf.fx.page(1);
        final var page1Bytes = Files.readAllBytes(page1.toPath());
        assertTrue(page1.setWritable(false));
        try {
            assertThrows(IOException.class, () -> rf.bulk(updates("a", "x")));
        } finally {
            assertTrue(page1.setWritable(true));
        }

        assertArrayEquals(page0, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertArrayEquals(page1Bytes, Files.readAllBytes(page1.toPath()));
        assertEquals(linesBefore, pkLines());
        assertEquals(0, rf.intents().length);
    }

    @Test
    public void aVacuumInTheMiddleOfABulkKeepsEveryRowOnItsRecord() throws Exception {
        rf.replacer.delete(rf.fx.indexed("a"));
        rf.replacer.delete(rf.fx.indexed("b"));
        final var deadBytes = rf.fx.page(0).length() - rf.fx.indexed("c").getLength();
        TestUtils.setPrivateField(configuration, "maxPageSize", rf.fx.page(0).length() + 4);
        assertTrue(deadBytes >= configuration.getMaxPageSize() / 4, "the fixture must make page 0 vacuumable");

        final var result = rf.bulk(updates("c", "x"));

        assertEquals(1, rf.vacuumed.size(), "page 0 lacked room for the copy and had a quarter page dead");
        assertEquals(0L, rf.vacuumed.getFirst().page());
        assertEquals(2, result.size());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(StorageFixture.record("c", "bulk-c"), rf.fx.readAt(rf.fx.indexed("c")));
        assertTrue(rf.fx.page(0).length() <= configuration.getMaxPageSize());
    }

    @Test
    public void aFullPageWithLittleDeadSpaceIsAppendedPastItsCap() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", rf.fx.page(0).length());

        rf.bulk(updates("a"));

        assertTrue(rf.vacuumed.isEmpty(), "a vacuum that frees less than a quarter page is not worth its rewrite");
        assertTrue(rf.fx.page(0).length() > configuration.getMaxPageSize());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void anEmptyBulkWritesNothing() throws Exception {
        final var linesBefore = pkLines();

        assertTrue(rf.bulk(List.of()).isEmpty());

        assertEquals(linesBefore, pkLines());
        assertEquals(0, rf.intents().length);
    }
}
