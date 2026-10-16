package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.ErrorCode;
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
        assertThrows(RuntimeException.class, () -> first.apply(document()));
        assertThrows(RuntimeException.class, () -> second.apply(document()));
        assertDoesNotThrow(() -> unbounded(null).close());
    }

    @Test
    public void oneCallableIsOpenedPerDistinctSource() {
        try (var context = unbounded(null)) {
            final var source = "let seen = 0; export default (doc) => ++seen;";
            final var first = context.callableFor(source);
            assertSame(first, context.callableFor(source));
            assertEquals(1d, first.apply(document()).asJsonNumber().getValue().doubleValue());
            assertEquals(2d, first.apply(document()).asJsonNumber().getValue().doubleValue());
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
    public void aContextWithoutScriptsRefusesToOpenOne() {
        try (var context = PipelineContext.withoutScripts()) {
            final var error = assertThrows(ScriptCallableException.class,
                    () -> context.callableFor("export default (doc) => 1;"));
            assertEquals("InternalError", error.getErrorName());
        }
    }
}
