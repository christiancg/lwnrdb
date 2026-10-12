package org.techhouse.unit.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AggregateDeadline;

public class AggregateDeadlineTest {
    private static Stream<Integer> thousand() {
        return IntStream.range(0, 1000).boxed();
    }

    @Test
    public void test_a_stream_is_untouched_without_an_open_deadline() {
        final var stream = thousand();
        assertSame(stream, AggregateDeadline.watch(stream));
        assertNull(AggregateDeadline.watch(null));
    }

    @Test
    public void test_an_expired_deadline_stops_the_stream() throws Exception {
        try (var _ = AggregateDeadline.open(1L)) {
            Thread.sleep(20);
            assertThrows(AggregateDeadline.Expired.class, () -> AggregateDeadline.watch(thousand()).toList());
        }
    }

    @Test
    public void test_a_disabled_deadline_never_expires() throws Exception {
        try (var _ = AggregateDeadline.open(0L)) {
            Thread.sleep(5);
            assertEquals(1000, AggregateDeadline.watch(thousand()).toList().size());
        }
    }

    @Test
    public void test_closing_a_nested_deadline_restores_the_outer_one() throws Exception {
        try (var _ = AggregateDeadline.open(60_000L)) {
            try (var _ = AggregateDeadline.open(1L)) {
                Thread.sleep(20);
                assertThrows(AggregateDeadline.Expired.class, () -> AggregateDeadline.watch(thousand()).toList());
            }
            assertEquals(1000, AggregateDeadline.watch(thousand()).toList().size());
        }
        final var stream = thousand();
        assertSame(stream, AggregateDeadline.watch(stream));
    }
}
