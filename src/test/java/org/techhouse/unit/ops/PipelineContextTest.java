package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.HeldRows;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PipelineContextTest {
    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static Stream<Integer> thousand() {
        return IntStream.range(0, 1000).boxed();
    }

    private static JsonObject document() {
        final var document = new JsonObject();
        document.add("price", new JsonNumber(1.0));
        return document;
    }

    private static PipelineContext unbounded(AnalyzeContext analyze) {
        return PipelineContext.of(0L, Long.MAX_VALUE, analyze);
    }

    @Test
    public void watchThrowsTheAggregateTimeoutAfterTheDeadline() throws Exception {
        try (var context = PipelineContext.of(1L, Long.MAX_VALUE, null)) {
            Thread.sleep(20);
            final var refused = assertThrows(AggregateLimitException.class, () -> context.watch(thousand()).toList());
            assertEquals(ErrorCode.AGGREGATE_TIMEOUT, refused.getErrorCode());
        }
    }

    @Test
    public void watchIsANoOpWithoutATimeout() throws Exception {
        try (var context = unbounded(null)) {
            final var stream = thousand();
            assertSame(stream, context.watch(stream));
            assertNull(context.watch(null));
            Thread.sleep(5);
            assertEquals(1000, context.watch(thousand()).toList().size());
        }
    }

    @Test
    public void test_a_tick_of_256_units_past_the_deadline_times_out() throws Exception {
        try (var context = PipelineContext.of(1L, Long.MAX_VALUE, null)) {
            Thread.sleep(20);
            final var refused = assertThrows(AggregateLimitException.class, () -> context.tick(256));
            assertEquals(ErrorCode.AGGREGATE_TIMEOUT, refused.getErrorCode());
        }
    }

    @Test
    public void test_ticks_carry_their_count_across_calls() throws Exception {
        try (var context = PipelineContext.of(1L, Long.MAX_VALUE, null)) {
            Thread.sleep(20);
            assertDoesNotThrow(() -> context.tick(255));
            assertThrows(AggregateLimitException.class, () -> context.tick(1));
        }
    }

    @Test
    public void test_a_tick_without_a_timeout_never_throws() throws Exception {
        try (var context = unbounded(null)) {
            Thread.sleep(5);
            assertDoesNotThrow(() -> context.tick(1_000_000));
        }
    }

    @Test
    public void chargeResultRefusesPastTheBudget() {
        try (var context = PipelineContext.of(0L, 10L, null)) {
            context.chargeResult(10);
            final var refused = assertThrows(AggregateLimitException.class, () -> context.chargeResult(1));
            assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE, refused.getErrorCode());
        }
        try (var fresh = PipelineContext.of(0L, 10L, null)) {
            assertDoesNotThrow(() -> fresh.chargeResult(10));
        }
    }

    @Test
    public void sourceCountsScannedDocumentsOnlyWhenAnalyzing() throws Exception {
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(), document(), document()));
        IocContainer.get(OperationProcessor.class).processMessage(bulk);
        final var analyze = new AnalyzeContext();
        try (var context = unbounded(analyze); var plain = unbounded(null)) {
            assertEquals(3, context.source(null, TestGlobals.DB, TestGlobals.COLL).count());
            assertEquals(3, plain.source(null, TestGlobals.DB, TestGlobals.COLL).count());
            final var provided = Stream.of(document());
            assertSame(provided, context.source(provided, TestGlobals.DB, TestGlobals.COLL));
        }
        assertEquals(3, analyze.getDocumentsScanned());
    }

    @Test
    public void indexUsedRecordsTheFieldAndLock() {
        final var analyze = new AnalyzeContext();
        try (var context = unbounded(analyze)) {
            context.indexUsed("status", "myDb|myColl|status");
            context.scanned(4);
        }
        assertEquals(List.of("status"), List.copyOf(analyze.getIndexesUsed()));
        assertEquals(List.of("myDb|myColl|status"), analyze.getLocksAcquired());
        assertEquals(4, analyze.getDocumentsScanned());
        try (var plain = unbounded(null)) {
            assertDoesNotThrow(() -> plain.indexUsed("status", "lock"));
        }
    }

    @Test
    public void closeClosesTheOpenedCallables() {
        final var context = unbounded(null);
        final var first = context.callableFor("export default (doc) => 1;");
        final var second = context.callableFor("export default (doc) => 2;");
        context.close();
        assertThrows(RuntimeException.class, () -> first.apply(document(), Set.of()));
        assertThrows(RuntimeException.class, () -> second.apply(document(), Set.of()));
        assertDoesNotThrow(() -> unbounded(null).close());
    }

    @Test
    public void oneCallableIsOpenedPerDistinctSource() {
        try (var context = unbounded(null)) {
            final var source = "let seen = 0; export default (doc) => ++seen;";
            final var first = context.callableFor(source);
            assertSame(first, context.callableFor(source));
            assertEquals(1d, first.apply(document(), Set.of()).asJsonNumber().getValue().doubleValue());
            assertEquals(2d, first.apply(document(), Set.of()).asJsonNumber().getValue().doubleValue());
            assertNotSame(first, context.callableFor("export default (doc) => 2;"));
        }
    }

    @Test
    public void anInvalidSourceReportsAScriptFailure() {
        try (var context = unbounded(null)) {
            final var error = assertThrows(ScriptCallableException.class,
                    () -> context.callableFor("export default (doc) => {"));
            assertEquals("SyntaxError", error.getErrorName());
        }
    }

    @Test
    public void chargeHeldSharesTheResultBudget() {
        try (var context = PipelineContext.of(0L, 10L, null)) {
            context.chargeHeld(6);
            context.chargeResult(4);
            final var refused = assertThrows(AggregateLimitException.class, () -> context.chargeHeld(1));
            assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE, refused.getErrorCode());
        }
    }

    @Test
    public void heldRowsSamplesAfterSixtyFour() {
        final var context = mock(PipelineContext.class);
        final var held = new HeldRows(context);
        final var huge = document();
        huge.add("blob", new JsonString("h".repeat(100_000)));
        final var medium = document();
        medium.add("blob", new JsonString("m".repeat(1_000)));
        final var small = document();
        final var rows = new ArrayList<JsonObject>();
        rows.add(huge);
        while (rows.size() < 200) {
            rows.add(rows.size() == 127 ? medium : small);
        }
        rows.forEach(held::hold);
        final var charged = ArgumentCaptor.forClass(Long.class);
        verify(context, times(200)).chargeHeld(charged.capture());
        final var charges = charged.getAllValues();
        final var hugeBytes = HeldRows.bytesOf(huge);
        final var smallBytes = HeldRows.bytesOf(small);
        final var mediumBytes = HeldRows.bytesOf(medium);
        assertEquals(hugeBytes, charges.getFirst());
        for (var i = 1; i < HeldRows.EXACT_ROWS; i++) {
            assertEquals(smallBytes, charges.get(i));
        }
        final var exactTotal = hugeBytes + (HeldRows.EXACT_ROWS - 1) * smallBytes;
        assertEquals(Math.ceilDiv(exactTotal, HeldRows.EXACT_ROWS), charges.get(HeldRows.EXACT_ROWS));
        assertEquals(Math.ceilDiv(exactTotal, HeldRows.EXACT_ROWS), charges.get(126));
        assertEquals(mediumBytes, charges.get(127));
        assertEquals(Math.ceilDiv(exactTotal + mediumBytes, HeldRows.EXACT_ROWS + 1), charges.get(128));
        assertEquals(smallBytes, charges.get(191));
        assertTrue(charges.stream().mapToLong(Long::longValue).sum() >= exactTotal + mediumBytes + 135 * smallBytes);
    }

    @Test
    public void aContextWithoutScriptsRefusesToOpenOne() {
        try (var context = PipelineContext.withoutScripts()) {
            final var error = assertThrows(ScriptCallableException.class,
                    () -> context.callableFor("export default (doc) => 1;"));
            assertEquals("InternalError", error.getErrorName());
        }
    }
}
