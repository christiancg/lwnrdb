package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;

public class EJsonInteropArrayShapeTest {
    private final SimpleJs engine = new SimpleJs();
    private final EJson eJson = new EJson();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    private JsonBaseElement returned(String source) {
        final var result = run(source);
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return result.getValue();
    }

    private void assertRefusedWithTypeError(String source) {
        final var result = run(source);
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void anIndexGetterIsStoredByItsValue() {
        final var value = returned("""
                const a = [1, 2, 3];
                Object.defineProperty(a, 1, { get() { return 42; }, enumerable: true });
                return a;
                """);
        assertEquals("[1,42,3]", eJson.toJson(value));
    }

    @Test
    public void aGetterOnAnEmptyArrayIsStored() {
        final var value = returned("""
                const c = [];
                Object.defineProperty(c, 0, { get() { return 7; }, enumerable: true });
                return c;
                """);
        assertEquals("[7]", eJson.toJson(value));
    }

    @Test
    public void aHoleIsStoredAsNull() {
        assertEquals("[1,null,3]", eJson.toJson(returned("return [1, , 3];")));
    }

    @Test
    public void aTrailingHoleKeepsTheArrayLength() {
        assertEquals("[1,null,null]", eJson.toJson(returned("const a = [1]; a.length = 3; return a;")));
    }

    @Test
    public void anArrayLongerThanTheDenseLimitIsRefused() {
        assertRefusedWithTypeError("const b = []; b[20000000] = 1; return b;");
        assertRefusedWithTypeError("return new Array(1e9);");
    }

    @Test
    public void aProxyIsStoredThroughItsGetTrap() {
        final var value = returned("""
                return new Proxy({ a: 1 }, { get(target, key) { return key === 'a' ? 99 : target[key]; } });
                """);
        assertEquals(99, value.asJsonObject().get("a").asJsonNumber().getValue().intValue());
    }

    @Test
    public void aProxyArrayIsStoredThroughItsTraps() {
        final var value = returned("""
                return new Proxy([1, 2], { get(target, key) { return key === '0' ? 5 : target[key]; } });
                """);
        assertEquals("[5,2]", eJson.toJson(value));
    }

    @Test
    public void aProxyOverAFunctionStoresNothing() {
        final var value = returned("return { f: new Proxy(function () {}, {}), kept: 1 };");
        assertFalse(value.asJsonObject().has("f"));
    }

    @Test
    public void aProxyWithoutOpsKeepsTheTargetConversion() {
        final var target = new JsObject();
        target.set("a", new JsNumber(1));
        final var converted = EJsonInterop.toHostEjson(new JsProxy(target, new JsObject()));
        assertEquals(1, converted.asJsonObject().get("a").asJsonNumber().getValue().intValue());
    }

    @Test
    public void aDenseArrayWithoutOpsIsConvertedByLength() {
        final var array = new JsArray(List.of(new JsNumber(1), new JsNumber(2)));
        assertEquals("[1,2]", eJson.toJson(EJsonInterop.toHostEjson(array)));
    }

    @Test
    public void jsonStringifyIsUnchanged() {
        final var value = returned("""
                const a = [1, 2, 3];
                Object.defineProperty(a, 1, { get() { return 42; }, enumerable: true });
                return JSON.stringify([a, [1, , 3]]);
                """);
        assertEquals("[[1,42,3],[1,null,3]]", value.asJsonString().getValue());
    }
}
