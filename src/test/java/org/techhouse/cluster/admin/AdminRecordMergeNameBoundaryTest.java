package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.HybridClock;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminRecordKey.Kind;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminRecordMergeNameBoundaryTest extends AdminRecordTestBase {
    private static final String MARKER = "peer_escape_marker";
    private static final long TWO_HOURS_MS = 7_200_000L;
    private long origClock;

    @BeforeEach
    public void resetClock() throws Exception {
        origClock = clock.current();
        TestUtils.getPrivateField(clock, "last", AtomicLong.class).set(0L);
    }

    @AfterEach
    public void restoreClock() {
        clock.observe(origClock);
    }

    private static Path dataRoot() {
        return Path.of(TestGlobals.PATH).toAbsolutePath();
    }

    private static AdminRecordMerge.Result merge(AdminRecord... records) {
        return AdminRecordMerge.apply(new ArrayList<>(Arrays.asList(records)), true);
    }

    private static long farFuture() {
        return HybridClock.pack(System.currentTimeMillis() + TWO_HOURS_MS, 0);
    }

    private AdminRecord traversalDatabase(long version) {
        final var valid = database("shop", version);
        return new AdminRecord(new AdminRecordKey(Kind.DATABASE, "../" + MARKER, "../" + MARKER), version,
                valid.body());
    }

    @Test
    public void aTraversalDatabaseRecordCreatesNoFolderOutsideTheDataRoot() {
        final var result = merge(traversalDatabase(clock.next()));
        assertTrue(result.complete());
        assertFalse(Files.exists(dataRoot().getParent().resolve(MARKER)));
        assertFalse(Files.exists(dataRoot().resolve(MARKER)));
        assertNull(cache.getAdminDbEntry("../" + MARKER));
    }

    @Test
    public void aTraversalProcedureRecordWritesNoFile() {
        final var result = merge(procedure(TestGlobals.DB, "../../" + MARKER, clock.next()));
        assertTrue(result.complete());
        assertFalse(Files.exists(dataRoot().resolve(MARKER + ".json")));
        assertFalse(Files.exists(dataRoot().getParent().resolve(MARKER + ".json")));
    }

    @Test
    public void aRejectedRecordDoesNotMakeTheMergeIncomplete() {
        final var result = merge(traversalDatabase(clock.next()), procedure("admin", "nightly", clock.next()));
        assertTrue(result.complete());
        assertFalse(result.changed());
    }

    @Test
    public void aRejectedRecordIsNotObservedByTheClock() {
        final var version = clock.next() + 1_000_000L;
        merge(traversalDatabase(version));
        assertTrue(clock.current() < version);
    }

    @Test
    public void aValidRecordNextToARejectedOneStillApplies() {
        final var result = merge(traversalDatabase(clock.next()), user("carol", clock.next()));
        assertTrue(result.complete());
        assertNotNull(cache.getAdminUserEntry("carol"));
    }

    @Test
    public void aNullOrKeylessRecordIsSkipped() {
        final var records = new ArrayList<AdminRecord>();
        records.add(null);
        records.add(new AdminRecord());
        records.add(user("dave", clock.next()));
        assertTrue(AdminRecordMerge.apply(records, true).complete());
        assertNotNull(cache.getAdminUserEntry("dave"));
    }

    @Test
    public void aRecordBeyondTheSkewIsDeferredLeavingTheMergeIncomplete() {
        final var version = farFuture();
        final var result = merge(user("erin", version));
        assertFalse(result.complete(), "a skewed record becomes acceptable later, so the sender must retry it");
        assertNull(cache.getAdminUserEntry("erin"));
        assertTrue(clock.current() < version);
    }

    @Test
    public void aCollectionWhoseIncarnationIsBeyondTheSkewIsDeferred() {
        final var incarnation = farFuture();
        final var result = merge(collection(TestGlobals.DB, "skewed", clock.next(), incarnation));
        assertFalse(result.complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "skewed"));
        assertTrue(clock.current() < incarnation);
    }

    @Test
    public void aRecordWithAnUnreadableVersionIsSkipped() throws Exception {
        final var record = user("frank", clock.next());
        final var field = AdminRecord.class.getDeclaredField("version");
        field.setAccessible(true);
        field.set(record, "not-a-number");
        assertTrue(merge(record).complete());
        assertNull(cache.getAdminUserEntry("frank"));
    }

    @Test
    public void aDeferredRecordDoesNotStopTheOthersFromApplying() {
        final var result = merge(user("gina", farFuture()), user("hank", clock.next()));
        assertFalse(result.complete());
        assertTrue(result.changed());
        assertNull(cache.getAdminUserEntry("gina"));
        assertNotNull(cache.getAdminUserEntry("hank"));
    }

    @Test
    public void aCollectionRecordNamingAnInvalidIndexFieldIsSkipped() {
        final var result = merge(collection(TestGlobals.DB, "indexed", clock.next(), "../" + MARKER));
        assertTrue(result.complete(), "an invalid index name never becomes valid, so it must not block the round");
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "indexed"));
        assertFalse(Files.exists(dataRoot().resolve(TestGlobals.DB).resolve(MARKER + ".building")));
    }

    @Test
    public void aCollectionRecordWithValidIndexFieldsApplies() {
        assertTrue(merge(collection(TestGlobals.DB, "indexed", clock.next(), "price", "address.city")).complete());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "indexed"));
    }

    @Test
    public void ordinaryRecordsStillApply() {
        assertTrue(merge(collection(TestGlobals.DB, "fresh", clock.next())).complete());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "fresh"));
    }
}
