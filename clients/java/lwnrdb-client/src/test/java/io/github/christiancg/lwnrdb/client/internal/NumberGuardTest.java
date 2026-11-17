package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

public class NumberGuardTest {
    @Test
    public void test_non_finite_numbers_are_refused_wherever_they_sit() {
        for (final var value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> NumberGuard.check(FakeServer.MAPPER.getNodeFactory().numberNode(value)));
            final var nested = FakeServer.MAPPER.createObjectNode();
            nested.putObject("a").putArray("b").add(1).add(value);
            final var error = assertThrows(IllegalArgumentException.class, () -> NumberGuard.check(nested));
            assertTrue(error.getMessage().contains("a.b[1]"), error.getMessage());
        }
    }

    @Test
    public void test_finite_extremes_pass() {
        final var node = FakeServer.MAPPER.createObjectNode().put("z", -0.0).put("big", 1e308)
                .put("tiny", Double.MIN_VALUE).put("exact", NumberGuard.MAX_EXACT_INTEGER).put("float", 1.5f);
        assertDoesNotThrow(() -> NumberGuard.check(node));
    }

    @Test
    public void test_whole_numbers_beyond_two_to_the_fifty_three_are_refused() {
        final var tooBig = FakeServer.MAPPER.createObjectNode().put("n", NumberGuard.MAX_EXACT_INTEGER + 1);
        assertThrows(IllegalArgumentException.class, () -> NumberGuard.check(tooBig));
        final var negative = FakeServer.MAPPER.createObjectNode().put("n", -NumberGuard.MAX_EXACT_INTEGER - 1);
        assertThrows(IllegalArgumentException.class, () -> NumberGuard.check(negative));
        final var huge = FakeServer.MAPPER.createObjectNode().put("n", BigInteger.TEN.pow(30));
        assertThrows(IllegalArgumentException.class, () -> NumberGuard.check(huge));
    }
}
