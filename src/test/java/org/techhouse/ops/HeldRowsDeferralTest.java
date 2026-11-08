package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class HeldRowsDeferralTest {
    private static JsonObject row(int i) {
        final var row = new JsonObject();
        row.add("_id", new JsonString("d" + i));
        row.add("n", new JsonNumber(i));
        row.add("payload", new JsonString("p".repeat(500 + i)));
        return row;
    }

    private static List<JsonObject> rows(int count) {
        final var rows = new ArrayList<JsonObject>();
        for (var i = 0; i < count; i++) {
            rows.add(row(i));
        }
        return rows;
    }

    private static long exactTotal(List<JsonObject> rows) {
        return rows.stream().mapToLong(HeldRows::bytesOf).sum();
    }

    private static List<Long> charges(boolean deferring) {
        final var count = 200;
        final var context = mock(PipelineContext.class);
        final var held = new HeldRows(context);
        when(context.defersHeld(same(held), anyLong())).thenReturn(deferring);
        rows(count).forEach(held::hold);
        final var charged = ArgumentCaptor.forClass(Long.class);
        verify(context, times(count)).chargeHeld(charged.capture());
        return charged.getAllValues();
    }

    @Test
    public void aSmallResultUnderARoomyBudgetIsNeverMeasured() {
        final var context = mock(PipelineContext.class);
        final var held = new HeldRows(context);
        when(context.defersHeld(same(held), anyLong())).thenReturn(true);

        rows(50).forEach(held::hold);

        verify(context, times(50)).defersHeld(same(held), anyLong());
        verify(context, never()).chargeHeld(anyLong());
    }

    @Test
    public void settlingChargesEveryDeferredRowItsExactBytesInOrder() {
        final var context = mock(PipelineContext.class);
        final var held = new HeldRows(context);
        when(context.defersHeld(same(held), anyLong())).thenReturn(true);
        final var rows = rows(10);
        rows.forEach(held::hold);

        held.settle();

        final var charged = ArgumentCaptor.forClass(Long.class);
        verify(context, times(10)).chargeHeld(charged.capture());
        assertEquals(rows.stream().map(HeldRows::bytesOf).toList(), charged.getAllValues());
        verify(context).settledHeld(rows.stream().mapToLong(HeldRows::bytesAtMost).sum());
    }

    @Test
    public void deferringChargesWhatTheExactPathChargesOnceSixtyFourRowsAreHeld() {
        assertEquals(charges(false), charges(true));
    }

    @Test
    public void theBoundNeverUndercountsARow() {
        for (final var row : rows(64)) {
            assertTrue(HeldRows.bytesAtMost(row) >= HeldRows.bytesOf(row));
        }
    }

    @Test
    public void aBudgetBetweenTheExactAndTheBoundedTotalStillAnswers() {
        final var rows = rows(10);
        final var exact = exactTotal(rows);
        assertTrue(rows.stream().mapToLong(HeldRows::bytesAtMost).sum() > exact);
        try (var context = PipelineContext.of(0L, exact, null)) {
            final var held = new HeldRows(context);
            assertDoesNotThrow(() -> rows.forEach(held::hold));
        }
        try (var context = PipelineContext.of(0L, exact - 1, null)) {
            final var held = new HeldRows(context);
            rows.subList(0, 9).forEach(held::hold);
            final var refused = assertThrows(AggregateLimitException.class, () -> held.hold(rows.getLast()));
            assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE, refused.getErrorCode());
        }
    }

    @Test
    public void anotherChargeSettlesDeferredRowsBeforeItIsJudged() {
        final var rows = rows(10);
        final var exact = exactTotal(rows);
        try (var context = PipelineContext.of(0L, exact + 5, null)) {
            final var held = new HeldRows(context);
            rows.forEach(held::hold);
            assertDoesNotThrow(() -> context.chargeResult(5));
            final var refused = assertThrows(AggregateLimitException.class, () -> context.chargeResult(1));
            assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE, refused.getErrorCode());
        }
    }
    private static final String LARGE = "x".repeat(500_000);
    private static final int SKEWED_ROWS = 10_000;

    private static JsonObject sized(int i, boolean large) {
        final var row = new JsonObject();
        row.add("_id", new JsonString("d" + i));
        row.add("pad", new JsonString(large ? LARGE : "p"));
        return row;
    }

    private static boolean sampled(int i) {
        return i <= HeldRows.EXACT_ROWS || i % HeldRows.EXACT_ROWS == 0;
    }

    @Test
    public void skewedRowsPastTheSampleAreStillCharged() {
        final var budget = 64L * 1024 * 1024;
        try (var context = PipelineContext.of(0L, budget, null)) {
            final var held = new HeldRows(context);
            final var exact = new long[1];
            final var refused = assertThrows(AggregateLimitException.class, () -> {
                for (var i = 1; i <= SKEWED_ROWS; i++) {
                    final var row = sized(i, !sampled(i));
                    exact[0] += HeldRows.bytesOf(row);
                    assertTrue(exact[0] <= budget * HeldRows.MAX_UNDERCHARGE, "held " + exact[0] + " bytes");
                    held.hold(row);
                }
            });
            assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE, refused.getErrorCode());
        }
    }

    @Test
    public void largeSampledRowsDoNotRefuseTheSmallOnesBetweenThem() {
        final var rows = new ArrayList<JsonObject>();
        for (var i = 1; i <= SKEWED_ROWS; i++) {
            rows.add(sized(i, sampled(i)));
        }
        final var bounded = rows.stream().mapToLong(HeldRows::bytesAtMost).sum();
        try (var context = PipelineContext.of(0L, bounded, null)) {
            final var held = new HeldRows(context);
            assertDoesNotThrow(() -> rows.forEach(held::hold));
        }
    }
}
