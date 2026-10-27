package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsValue;

public class EJsonInteropViewTest {
    private static final EJson EJSON = new EJson();

    private static JsonObject document() {
        final var nested = new JsonObject();
        nested.add("deep", new JsonString("x"));
        final var list = new JsonArray();
        list.add(new JsonNumber(1));
        list.add(new JsonNumber(2));
        final var document = new JsonObject();
        document.add("_id", new JsonString("d1"));
        document.add("a", new JsonNumber(1));
        document.add("nested", nested);
        document.add("list", list);
        document.add("text", new JsonString("t"));
        return document;
    }

    private static boolean deferred(JsObject object, String key) {
        final JsValue raw = object.getProperties().get(key);
        return raw != null && "DeferredEJson".equals(raw.getClass().getSimpleName());
    }

    private static String fold(String body) {
        try (var callable = new SimpleJs().openCallable("export default (acc, doc) => { " + body + " };",
                new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited()))) {
            return EJSON.toJson(callable.apply(new JsonNumber(0), document()));
        }
    }

    @Test
    public void test_a_view_resolves_only_what_is_read() {
        final var view = EJsonInterop.viewOf(document(), Set.of());
        assertEquals(List.of("_id", "a", "nested", "list", "text"), List.copyOf(view.keys()));
        assertTrue(deferred(view, "a"));
        assertTrue(deferred(view, "nested"));
        assertEquals(1, ((JsNumber) view.get("a")).getValue());
        assertFalse(deferred(view, "a"));
        assertTrue(deferred(view, "text"));
        final var nested = assertInstanceOf(JsObject.class, view.get("nested"));
        assertTrue(deferred(nested, "deep"));
        assertInstanceOf(JsArray.class, view.get("list"));
    }

    @Test
    public void test_eager_roots_convert_at_creation() {
        final var view = EJsonInterop.viewOf(document(), Set.of("nested"));
        assertFalse(deferred(view, "nested"));
        assertTrue(deferred(view, "a"));
        assertInstanceOf(JsNumber.class, EJsonInterop.viewOrValue(new JsonNumber(3)));
    }

    @Test
    public void test_a_view_answers_like_an_eager_copy() {
        assertEquals("\"_id,a,nested,list,text\"", fold("return Object.keys(doc).join(',');"));
        assertEquals(
                "\"{\\\"_id\\\":\\\"d1\\\",\\\"a\\\":1,\\\"nested\\\":{\\\"deep\\\":\\\"x\\\"},\\\"list\\\":[1,2],\\\"text\\\":\\\"t\\\"}\"",
                fold("return JSON.stringify(doc);"));
        assertEquals("{\"_id\":\"d1\",\"a\":1,\"nested\":{\"deep\":\"x\"},\"list\":[1,2],\"text\":\"t\",\"b\":2}",
                fold("return { ...doc, b: 2 };"));
        assertEquals("\"_id,nested,list,text\"", fold("delete doc.a; return Object.keys(doc).join(',');"));
        assertEquals("\"1,false,_id,nested,list,text\"", fold("Object.defineProperty(doc, 'a', { enumerable: false });"
                + " return [doc.a, Object.getOwnPropertyDescriptor(doc, 'a').enumerable, Object.keys(doc)].join(',');"));
        assertEquals("{\"_id\":\"d1\",\"a\":1,\"nested\":{\"deep\":\"x\"},\"list\":[1,2],\"text\":\"t\"}",
                fold("return doc;"));
        assertEquals("\"x,2\"", fold("doc.nested.deep += ''; return [doc.nested.deep, doc.list.length].join(',');"));
    }
}
