package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.BigIntBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.builtins.ObjectProtoBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.StringBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;

public class IntrinsicsTest {
    private static String run(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    // An unknown name is not resolved by any family
    @Test
    public void test_unknown_name_is_not_resolved() {
        assertNull(ArrayBuiltins.getMethod(new JsArray(), "nope", null, null));
        assertNull(StringBuiltins.getMethod(new JsString("a"), "nope", null, null));
        assertNull(NumberBuiltins.getMethod(new JsNumber(1), "nope"));
        assertNull(BigIntBuiltins.getMethod(new JsBigInt(BigInteger.ONE), "nope"));
        assertNull(SymbolBuiltins.getMethod(new JsSymbol("s"), "nope"));
        assertNull(SymbolBuiltins.getProperty(new JsSymbol("s"), "nope"));
        assertNull(RegexBuiltins.getMethod(RegexTranslator.compile("a", ""), "nope"));
        assertNull(ObjectProtoBuiltins.getMethod(new JsObject(), "nope", null, null));
        assertNull(FunctionProtoBuiltins.getMethod(new JsNativeFunction("f", (_, _) -> null), "nope", null, null));
        assertNull(FunctionProtoBuiltins.metadata(new JsNativeFunction("f", (_, _) -> null), "nope"));
    }

    // a subclass of BigInt with no JsObject internal state wraps the produced primitive, so a
    // BigInt.prototype method resolves the receiver via the unwrap fallback rather than direct instanceof
    @Test
    public void test_bigint_subclass_unwraps() {
        assertEquals("5", run("class B extends BigInt { constructor(v) { super(v); } } new B(5).toString()"));
    }

    // a subclass of RegExp wraps the produced primitive for RegExp.prototype methods
    @Test
    public void test_regexp_subclass_unwraps() {
        assertTrue(bool("class R extends RegExp { constructor(p) { super(p); } } new R('a').test('a')"));
    }

    // a subclass of Promise wraps the produced primitive for Promise.prototype methods
    @Test
    public void test_promise_subclass_unwraps() {
        assertEquals("object", run("class P extends Promise { constructor(e) { super(e); } } "
                + "typeof new P((res) => res(1)).then(() => {})"));
    }

    // a subclass of Set wraps the produced primitive for Set.prototype methods
    @Test
    public void test_set_subclass_unwraps() {
        assertTrue(bool("class MySet extends Set { constructor(v) { super(v); } } new MySet([1, 2]).has(1)"));
    }

    // a subclass of Date wraps the produced primitive for Date.prototype methods
    @Test
    public void test_date_subclass_unwraps() {
        assertEquals(0, num("class D extends Date { constructor(v) { super(v); } } new D(0).getTime()"));
    }

    // a subclass of DataView wraps the produced primitive for DataView.prototype methods
    @Test
    public void test_data_view_subclass_unwraps() {
        assertEquals(0,
                num("class V extends DataView { constructor(b) { super(b); } } new V(new ArrayBuffer(4)).getInt8(0)"));
    }

    // %ThrowTypeError% is one frozen, anonymous function shared by every poison-pill accessor
    @Test
    public void throwTypeErrorIsASharedFrozenIntrinsic() {
        assertTrue(bool("""
                const callee = Object.getOwnPropertyDescriptor(function () { return arguments; }(), 'callee').get;
                const caller = Object.getOwnPropertyDescriptor(Function.prototype, 'caller');
                callee === caller.get && callee === caller.set && Object.isFrozen(callee)
                    && callee.name === '' && callee.length === 0 && callee.prototype === undefined
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("(function () { return arguments; })().callee"));
    }

    // Generator/async function objects sit on their own intrinsic prototypes, not Function.prototype
    @Test
    public void generatorAndAsyncFunctionIntrinsicsExist() {
        assertEquals("GeneratorFunction", run("Object.getPrototypeOf(function* () {}).constructor.name"));
        assertEquals("AsyncFunction", run("Object.getPrototypeOf(async function () {}).constructor.name"));
        assertEquals("AsyncGeneratorFunction", run("Object.getPrototypeOf(async function* () {}).constructor.name"));
        assertTrue(bool("function* g() {} Object.getPrototypeOf(Object.getPrototypeOf(g)) === Function.prototype"));
        assertTrue(bool("""
                function* g() {}
                Object.getPrototypeOf(Object.getPrototypeOf(g.prototype)) === Iterator.prototype
                """));
        assertTrue(bool("""
                async function* g() {}
                Object.getPrototypeOf(Object.getPrototypeOf(g.prototype)) === AsyncIterator.prototype
                """));
    }

    // A regex own property is a real own property, so RegExpExec can see an overridden `exec`
    @Test
    public void regExpOwnPropertyAssignmentLands() {
        assertEquals(1, num("const r = /b/; r.exec = () => 1; r.exec()"));
        assertEquals("lastIndex,exec", run("const r = /b/; r.exec = () => 1; Object.getOwnPropertyNames(r).join(',')"));
        assertTrue(bool("const r = /b/; r.exec = () => 1; typeof /c/.exec === 'function'"));
    }

    // Map.prototype.size/Set.prototype.size are real accessor properties, reachable off a foreign
    // receiver (which must throw) and off a builtin-subclass instance (which wraps its JsMap/JsSet in
    // the JsObject primitive slot, so the accessor's brand check has to unwrap it, not reject it).
    @Test
    public void mapAndSetSizeAreAccessorsThatHandleSubclasses() {
        assertEquals(2, num("(new Map([['a', 1], ['b', 2]])).size"));
        assertEquals(2, num("(new Set([1, 2])).size"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(Map.prototype, 'size').get !== undefined"
                + " && Object.getOwnPropertyDescriptor(Map.prototype, 'size').set === undefined"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(Map.prototype, 'size').get.call({})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(Set.prototype, 'size').get.call(new Map())"));
        assertEquals(2,
                num("class MyMap extends Map { constructor(v) { super(v); } } new MyMap([['a', 1], ['b', 2]]).size"));
        assertEquals(3, num("class MySet extends Set { constructor(v) { super(v); } } "
                + "const s = new MySet([1, 2]); s.add(3); s.size"));
        assertTrue(bool("Set.prototype.keys === Set.prototype.values"));
    }
}
