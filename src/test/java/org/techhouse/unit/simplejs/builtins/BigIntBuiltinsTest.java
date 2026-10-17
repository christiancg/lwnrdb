package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;

public class BigIntBuiltinsTest {
    private static final long PRE_CHANGE_TO_STRING_INSTRUCTIONS = 933;
    private static final long CONVERSION_PACE_INSTRUCTIONS = 931;

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static long instructions(String source) {
        final var result = new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(-1, 30_000, -1, -1)));
        assertFalse(result.isError(), result.getErrorMessage());
        return result.getMetrics().instructions();
    }

    @Test
    public void bigintParameterRunsToBigInt() {
        assertTrue(bool("BigInt.asIntN(2, false) === 0n"));
        assertTrue(bool("BigInt.asIntN(2, true) === 1n"));
        assertTrue(bool("BigInt.asIntN(2, '1') === 1n"));
        assertTrue(bool("BigInt.asIntN(2, '') === 0n"));
        assertTrue(bool("BigInt.asIntN(2, '     ') === 0n"));
        assertTrue(bool("BigInt.asIntN(3, '0b1010') === 2n"));
        assertTrue(bool("BigInt.asIntN(3, '0o12') === 2n"));
        assertTrue(bool("BigInt.asIntN(3, '   0xa   ') === 2n"));
        assertTrue(bool("BigInt.asIntN(2, []) === 0n"));
        assertTrue(bool("BigInt.asIntN(2, [1]) === 1n"));
        assertTrue(bool("BigInt.asIntN(2, Object(1n)) === 1n"));
        assertTrue(bool("BigInt.asUintN(3, { valueOf() { return 10n } }) === 2n"));
        assertTrue(bool("BigInt.asIntN(4, '12345678901234567890003') === 3n"));
    }

    @Test
    public void bigintParameterRejectsIncompatibleValues() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, undefined)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, Symbol('1'))"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("BigInt.asIntN(0, { valueOf() { return Symbol('1') } })"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, 'a')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, '0b2')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("BigInt.asIntN(0, '1n')"));
    }

    @Test
    public void bitsParameterRunsToIndex() {
        assertTrue(bool("BigInt.asIntN(Object(0), 1n) === 0n"));
        assertTrue(bool("BigInt.asIntN(Object(NaN), 1n) === 0n"));
        assertTrue(bool("BigInt.asIntN({ valueOf() { return NaN } }, 1n) === 0n"));
        assertTrue(bool("BigInt.asIntN({ toString() { return null } }, 1n) === 0n"));
        assertTrue(bool("BigInt.asIntN(Object(true), 1n) === -1n"));
        assertTrue(bool("BigInt.asIntN({ valueOf() { return '1' } }, 1n) === -1n"));
        assertTrue(bool("BigInt.asIntN(2.9, 5n) === 1n"));
    }

    @Test
    public void bitsParameterRejectsOutOfRangeValues() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(-1, 0n)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(-2.5, 0n)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN('-2.5', 0n)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(-Infinity, 0n)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(9007199254740992, 0n)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(Infinity, 0n)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("BigInt.asIntN(1e9, 0n)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(0n, 0n)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.asIntN(Symbol('1'), 0n)"));
    }

    @Test
    public void toStringIsChargedOnce() {
        final var bigint = "const x = (1n << 1000000n) - 1n; ";
        assertEquals(PRE_CHANGE_TO_STRING_INSTRUCTIONS, instructions(bigint + "x.toString(); return 1;"));
        assertEquals(PRE_CHANGE_TO_STRING_INSTRUCTIONS, instructions(bigint + "x.toString(16); return 1;"));
        assertEquals(PRE_CHANGE_TO_STRING_INSTRUCTIONS, instructions(bigint + "x.toLocaleString(); return 1;"));
        assertEquals(CONVERSION_PACE_INSTRUCTIONS, instructions(bigint + "x + ''; return 1;"));
        assertEquals(PRE_CHANGE_TO_STRING_INSTRUCTIONS - CONVERSION_PACE_INSTRUCTIONS,
                instructions("const x = (1n << 64n) - 1n; x.toString(); return 1;"));
    }

    @Test
    public void parametersAreCoercedInOrder() {
        assertTrue(bool("""
                let i = 0;
                let bits = { valueOf() { if (i !== 0) throw new Error('order'); i++; return 0 } };
                let value = { valueOf() { if (i !== 1) throw new Error('order'); i++; return 0n } };
                BigInt.asIntN(bits, value);
                i === 2
                """));
    }
}
