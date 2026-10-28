package org.techhouse.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.AggregateRequest;

public class ListenGroupTest {
    private static ListenGroup group() {
        final var request = new AggregateRequest("db", "coll");
        request.setAggregationSteps(List.of());
        return new ListenGroup("key", request, Set.of("db|coll"), false);
    }

    @Test
    public void anIdleGroupStartsARunAndFinishesWithoutARerun() {
        final var group = group();

        assertTrue(group.tryStartRun());
        assertFalse(group.finishRun(), "nothing asked for another run");
        assertTrue(group.tryStartRun(), "a finished run leaves the group idle");
    }

    @Test
    public void aRequestDuringARunIsRefusedAndAsksForOneRerun() {
        final var group = group();
        assertTrue(group.tryStartRun());

        assertFalse(group.tryStartRun(), "a second worker never runs the group alongside the first");
        assertFalse(group.tryStartRun(), "further requests collapse into the one pending rerun");

        assertTrue(group.finishRun(), "the run that finishes re-queues the group once");
        assertTrue(group.tryStartRun(), "the re-queued work item starts the next run");
        assertFalse(group.finishRun());
    }

    @Test
    public void identicalRegistrationsShareAKeyAndDistinctOnesDoNot() {
        final var request = new AggregateRequest("db", "coll");
        request.setAggregationSteps(List.of());
        final var same = new AggregateRequest("db", "coll");
        same.setAggregationSteps(List.of());
        final var other = new AggregateRequest("db", "other");
        other.setAggregationSteps(List.of());

        assertEquals(ListenGroups.keyOf("u", request), ListenGroups.keyOf("u", same));
        assertNotEquals(ListenGroups.keyOf("u", request), ListenGroups.keyOf("v", request));
        assertNotEquals(ListenGroups.keyOf("u", request), ListenGroups.keyOf("u", other));
    }

    @Test
    public void theLastMemberLeavingDropsTheGroupAndALaterJoinStartsAFreshOne() {
        final var groups = new ListenGroups();
        final var request = new AggregateRequest("db", "coll");
        request.setAggregationSteps(List.of());
        final var first = UUID.randomUUID();
        final var group = groups.join("k", request, Set.of("db|coll"), first);

        groups.leave(UUID.randomUUID(), first);
        assertSame(group, groups.byId(group.id()), "leaving an unknown group changes nothing");
        groups.leave(group.id(), first);
        assertNull(groups.byId(group.id()));
        groups.leave(group.id(), first);

        final var second = UUID.randomUUID();
        final var replacement = groups.join("k", request, Set.of("db|coll"), second);
        assertNotEquals(group.id(), replacement.id());
        assertEquals(Set.of(second), replacement.members());
        assertEquals(1, groups.size());
    }
}
