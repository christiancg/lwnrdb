package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;

public class HybridClockTest {
    @Test
    public void test_two_writes_in_one_millisecond_produce_distinct_wire_versions() {
        final var eJson = IocContainer.get(EJson.class);
        final var clock = new HybridClock();
        final var seen = new HashSet<Long>();
        for (var i = 0; i < 64; i++) {
            final var version = clock.next();
            final var onTheWire = eJson.fromJson(eJson.toJson(new DigestEntry("a", version, false)), DigestEntry.class)
                    .versionValue();
            assertEquals(version, onTheWire);
            assertTrue(seen.add(onTheWire), "wire version " + onTheWire + " collided with an earlier write");
        }
    }

    @Test
    public void test_pack_and_unpack_round_trip() {
        final var packed = HybridClock.pack(1_700_000_000_000L, 42L);
        assertEquals(1_700_000_000_000L, HybridClock.physicalOf(packed));
        assertEquals(42L, HybridClock.logicalOf(packed));
    }

    @Test
    public void test_next_is_strictly_increasing_within_one_millisecond() {
        final var clock = new HybridClock();
        var previous = clock.next();
        for (var i = 0; i < 5_000; i++) {
            final var current = clock.next();
            assertTrue(current > previous, "version " + current + " must exceed " + previous);
            previous = current;
        }
    }

    @Test
    public void test_logical_counter_borrows_into_the_next_millisecond_when_exhausted() {
        final var clock = new HybridClock();
        final var future = System.currentTimeMillis() + 100_000L;
        clock.seed(HybridClock.pack(future, 65_535L));
        final var next = clock.next();
        assertEquals(future + 1, HybridClock.physicalOf(next));
        assertEquals(0L, HybridClock.logicalOf(next));
    }

    @Test
    public void test_observe_pulls_the_clock_forward() {
        final var clock = new HybridClock();
        final var farFuture = HybridClock.pack(System.currentTimeMillis() + 1_000_000L, 0);
        clock.observe(farFuture);
        assertTrue(clock.next() > farFuture);
    }

    @Test
    public void test_observe_never_moves_the_clock_backwards() {
        final var clock = new HybridClock();
        final var first = clock.next();
        clock.observe(1L);
        assertTrue(clock.next() > first);
    }

    @Test
    public void test_seed_never_moves_the_clock_backwards() {
        final var clock = new HybridClock();
        final var high = HybridClock.pack(System.currentTimeMillis() + 500_000L, 0);
        clock.seed(high);
        clock.seed(1L);
        assertEquals(high, clock.current());
    }

    @Test
    public void test_a_seeded_clock_outranks_everything_written_before_the_restart() {
        final var beforeRestart = new HybridClock();
        final var lastWritten = beforeRestart.next();

        final var afterRestart = new HybridClock();
        afterRestart.seed(lastWritten);
        assertTrue(afterRestart.next() > lastWritten,
                "a restarted node must not assign a version at or below what it already wrote");
    }

    @Test
    public void test_an_unseeded_clock_is_the_regression_this_guards() {
        final var unseeded = new HybridClock();
        assertEquals(0L, unseeded.current());
    }

    @Test
    public void test_physical_is_clamped_to_the_representable_range() {
        final var packed = HybridClock.pack(Long.MAX_VALUE, 0);
        assertTrue(packed > 0, "a clamped version must stay positive rather than overflow into negatives");
    }

    @Test
    public void test_a_negative_physical_is_clamped_to_zero() {
        assertEquals(0L, HybridClock.physicalOf(HybridClock.pack(-5L, 0)));
    }
}
