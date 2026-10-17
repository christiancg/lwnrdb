package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.BigIntLimits;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;

public class BigIntLimitsTest {
    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    private static ScriptResult run(String source, long memoryBudget) {
        return assertTimeoutPreemptively(PROMPTLY,
                () -> new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), null, null,
                        new ResourceLimits(-1, 30_000, -1, memoryBudget))));
    }

    private static void assertTooBig(String expression) {
        final var result = run("return String(" + expression + ").length;", -1);
        assertTrue(result.isError(), expression);
        assertEquals("RangeError", result.getErrorName());
        assertEquals(BigIntLimits.EXCEEDED, result.getErrorMessage());
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"3n ** 100000000n", "2n ** (2n ** 28n)", "2n ** (2n ** 40n)", "1n << 20000000n",
            "1n << (2n ** 40n)", "1n >> -(2n ** 40n)", "(1n << 16000000n) * (1n << 16000000n)",
            "BigInt.asUintN(2 ** 25, -1n)", "BigInt.asIntN(2 ** 30, 5n)"})
    public void test_huge_results_are_range_errors(String expression) {
        assertTooBig(expression);
    }

    @Test
    public void test_pow_exponent_beyond_int_is_range_error_not_arithmetic_exception() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("7n ** (2n ** 40n)"));
    }

    @Test
    public void test_range_error_is_catchable() {
        final var result = run("try { 3n ** 100000000n; } catch (e) { return e.name + ':' + e.message; }", -1);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("RangeError:" + BigIntLimits.EXCEEDED, result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_result_at_cap_is_charged_to_memory_budget() {
        final var result = run("const x = 1n << 16000000n; return 1;", 1024 * 1024);
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName());
    }

    @Test
    public void test_result_below_cap_fits_a_large_budget() {
        final var result = run("const x = 1n << 16000000n; return (x >> 15999999n).toString();", 64L << 20);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("2", result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_small_bigints_unchanged() {
        assertTrue(bool("2n ** 64n === 18446744073709551616n"));
        assertTrue(bool("(5n - 7n) * 3n + 1n === -5n"));
        assertTrue(bool("(1n << 3n) === 8n && (8n >> 2n) === 2n && (8n << -2n) === 2n && (2n >> -3n) === 16n"));
        assertTrue(bool("(-9n >> 100n) === -1n && (9n >> 100n) === 0n && (0n << (2n ** 40n)) === 0n"));
        assertTrue(bool("(-9n >> (2n ** 40n)) === -1n && (9n >> (2n ** 40n)) === 0n"));
        assertTrue(bool("BigInt.asUintN(64, -1n) === 18446744073709551615n && BigInt.asIntN(8, 255n) === -1n"));
    }

    @Test
    public void test_bigint_exponent_zero_and_one_edge() {
        assertTrue(bool("0n ** 0n === 1n && 5n ** 0n === 1n && 5n ** 1n === 5n"));
        assertTrue(bool("0n ** (2n ** 40n) === 0n && 1n ** (2n ** 40n) === 1n"));
        assertTrue(bool("(-1n) ** (2n ** 40n) === 1n && (-1n) ** (2n ** 40n + 1n) === -1n"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("2n ** -1n"));
    }

    @Test
    public void test_check_bits_charges_only_beyond_a_machine_word() {
        final var ops = mock(InterpreterOps.class);
        BigIntLimits.checkBits(Long.SIZE, ops);
        verify(ops, never()).charge(anyLong());
        BigIntLimits.checkBits(Long.SIZE + 1, ops);
        verify(ops).charge(9);
        BigIntLimits.checkBits(BigIntLimits.MAX_BITS, null);
        assertThrows(RangeErrorException.class, () -> BigIntLimits.checkBits(BigIntLimits.MAX_BITS + 1, ops));
        assertEquals(BigInteger.valueOf(6), BigIntLimits.multiply(BigInteger.TWO, BigInteger.valueOf(3), ops));
    }
}
