package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.test.TestGlobals;

public class WriteEffectsDurablePrefixTest {
    private static final String SUBJECT = TestGlobals.DB + "|" + TestGlobals.COLL;

    private static AdminTriggerRunEntry run(String runId) {
        return new AdminTriggerRunEntry(runId, 0, "local", TestGlobals.DB, TestGlobals.COLL, "audit", "record",
                EventType.UPDATED, false, "admin", 0, 0L, List.of(runId), List.of());
    }

    @Test
    public void abandonKeepsTheEffectsOfTheWritesForcedBeforeTheGroupWasReopened() throws Exception {
        final var discardedRuns = new ArrayList<String>();
        final var retracted = new ArrayList<String>();
        try (var log = mockStatic(TriggerRunLog.class, CALLS_REAL_METHODS)) {
            log.when(() -> TriggerRunLog.discardAll(any())).thenAnswer(invocation -> {
                discardedRuns.addAll(invocation.<Collection<String>>getArgument(0));
                return null;
            });
            final var effects = WriteEffects.grouped();
            final var durable = mock(StagedTriggerRuns.class);
            final var lost = mock(StagedTriggerRuns.class);
            effects.stagingSink().save(run("durable-run"));
            final var stagedByDurableWrite = effects.stagedCount();
            effects.stagingSink().save(run("lost-run"));

            effects.submitLanded(durable, Map.of(), TestGlobals.DB, TestGlobals.COLL, "admin", 0);
            effects.replicateDeleteLater("kept", 1L, () -> retracted.add("kept"));
            effects.markDurablePrefix(stagedByDurableWrite);
            effects.submitLanded(lost, Map.of(), TestGlobals.DB, TestGlobals.COLL, "admin", 0);
            effects.replicateDeleteLater("gone", 2L, () -> retracted.add("gone"));
            effects.replicateUpsertLater("upserted-after");

            effects.abandon(SUBJECT);

            verify(durable).submitLanded(anyMap(), anyString(), anyString(), anyString(), anyInt());
            verify(lost, never()).submitLanded(anyMap(), anyString(), anyString(), anyString(), anyInt());
            assertEquals(List.of("lost-run"), discardedRuns);
            assertEquals(List.of("gone"), retracted);
            assertEquals(Map.of("kept", 1L), effects.durablePrefixDeleteVersions());
            assertEquals(List.of(), effects.durablePrefixUpsertIds());
        }
    }

    @Test
    public void withoutAReopenAbandonDiscardsEverything() throws Exception {
        final var discardedRuns = new ArrayList<String>();
        final var retracted = new ArrayList<String>();
        try (var log = mockStatic(TriggerRunLog.class, CALLS_REAL_METHODS)) {
            log.when(() -> TriggerRunLog.discardAll(any())).thenAnswer(invocation -> {
                discardedRuns.addAll(invocation.<Collection<String>>getArgument(0));
                return null;
            });
            final var effects = WriteEffects.grouped();
            final var staged = mock(StagedTriggerRuns.class);
            effects.stagingSink().save(run("first"));
            effects.stagingSink().save(run("second"));
            effects.submitLanded(staged, Map.of(), TestGlobals.DB, TestGlobals.COLL, "admin", 0);
            effects.replicateDeleteLater("id", 1L, () -> retracted.add("id"));

            effects.abandon(SUBJECT);

            verify(staged, never()).submitLanded(anyMap(), anyString(), anyString(), anyString(), anyInt());
            assertEquals(List.of("first", "second"), discardedRuns);
            assertEquals(List.of("id"), retracted);
        }
    }
}
