package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DbModule {
    private DbModule() {
    }

    public static JsObject create(DatabaseAccess database) {
        final var db = new JsObject();
        db.set("findById", new JsNativeFunction("findById",
                (_, args) -> EJsonInterop.fromEjson(database.findById(arg(args, 0), arg(args, 1), arg(args, 2)))));
        db.set("aggregate", new JsNativeFunction("aggregate", (_, args) -> aggregate(database, args)));
        db.set("save", new JsNativeFunction("save", (_, args) -> save(database, args)));
        db.set("delete", new JsNativeFunction("delete", (_, args) -> {
            database.delete(arg(args, 0), arg(args, 1), arg(args, 2));
            return JsUndefined.getInstance();
        }));
        db.set("listCollections", new JsNativeFunction("listCollections",
                (_, args) -> toStringArray(database.listCollections(arg(args, 0)))));
        db.set("listDatabases",
                new JsNativeFunction("listDatabases", (_, _) -> toStringArray(database.listDatabases())));
        return db;
    }

    private static JsValue aggregate(DatabaseAccess database, List<JsValue> args) {
        final var pipeline = (JsonArray) EJsonInterop.toEjson(args.get(2));
        final var results = database.aggregate(arg(args, 0), arg(args, 1), pipeline);
        final var array = new JsArray();
        for (final var result : results) {
            array.push(EJsonInterop.fromEjson(result));
        }
        return array;
    }

    private static JsValue save(DatabaseAccess database, List<JsValue> args) {
        final var document = (JsonObject) EJsonInterop.toEjson(args.get(2));
        return EJsonInterop.fromEjson(database.save(arg(args, 0), arg(args, 1), document));
    }

    private static JsValue toStringArray(List<String> values) {
        final var array = new JsArray();
        for (final var value : values) {
            array.push(new JsString(value));
        }
        return array;
    }

    private static String arg(List<JsValue> args, int index) {
        return index < args.size() ? JsCoercion.toStr(args.get(index)) : "undefined";
    }
}
