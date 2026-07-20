package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsonBuiltins {
    private static final EJson EJSON = new EJson();

    private JsonBuiltins() {
    }

    public static JsObject create() {
        final var json = new JsObject();
        json.set("parse", new JsNativeFunction("parse", (_, args) -> parse(args)));
        json.set("stringify", new JsNativeFunction("stringify", (_, args) -> stringify(args)));
        return json;
    }

    private static JsValue parse(List<JsValue> args) {
        final var source = args.isEmpty() ? "undefined" : args.getFirst();
        if (!(source instanceof JsString string)) {
            throw new SyntaxErrorException("Unexpected token in JSON");
        }
        final JsonBaseElement element;
        try {
            element = EJSON.fromJson("{\"v\":" + string.getValue() + "}", JsonObject.class).get("v");
        } catch (RuntimeException e) {
            throw new SyntaxErrorException("Unexpected token in JSON: " + e.getMessage());
        }
        return EJsonInterop.fromEjson(element);
    }

    private static JsValue stringify(List<JsValue> args) {
        if (args.isEmpty()) {
            return JsUndefined.getInstance();
        }
        final var element = EJsonInterop.toEjson(args.getFirst());
        if (element == null) {
            return JsUndefined.getInstance();
        }
        return new JsString(EJSON.toJson(element));
    }
}
