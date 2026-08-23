package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.BulkSaveOutcome;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DbModule {
    private DbModule() {
    }

    public static JsObject create(DatabaseAccess database, InterpreterOps ops) {
        final var db = new JsObject();
        db.set("findById", new JsNativeFunction("findById",
                (_, args) -> EJsonInterop.fromEjson(database.findById(arg(args, 0), arg(args, 1), arg(args, 2)))));
        db.set("aggregate", new JsNativeFunction("aggregate", (_, args) -> aggregate(database, args)));
        db.set("save", new JsNativeFunction("save", (_, args) -> save(database, args)));
        db.set("bulkSave", new JsNativeFunction("bulkSave", (_, args) -> bulkSave(database, args)));
        db.set("delete", new JsNativeFunction("delete", (_, args) -> {
            database.delete(arg(args, 0), arg(args, 1), arg(args, 2));
            return JsUndefined.getInstance();
        }));
        db.set("listCollections", new JsNativeFunction("listCollections",
                (_, args) -> toStringArray(database.listCollections(arg(args, 0)))));
        db.set("listDatabases",
                new JsNativeFunction("listDatabases", (_, _) -> toStringArray(database.listDatabases())));
        db.set("transaction", new JsNativeFunction("transaction", (_, args) -> transaction(database, ops, args)));
        final var scopedDatabase = database.scopedDatabase();
        if (scopedDatabase != null) {
            db.set("name", new JsString(scopedDatabase));
        }
        return db;
    }

    private static JsValue aggregate(DatabaseAccess database, List<JsValue> args) {
        final var pipeline = (JsonArray) EJsonInterop.toHostEjson(args.get(2));
        final var results = database.aggregate(arg(args, 0), arg(args, 1), pipeline);
        final var array = new JsArray();
        for (final var result : results) {
            array.push(EJsonInterop.fromEjson(result));
        }
        return array;
    }

    private static JsValue save(DatabaseAccess database, List<JsValue> args) {
        final var document = (JsonObject) EJsonInterop.toHostEjson(args.get(2));
        return EJsonInterop.fromEjson(database.save(arg(args, 0), arg(args, 1), document));
    }

    private static JsValue bulkSave(DatabaseAccess database, List<JsValue> args) {
        final var converted = EJsonInterop.toHostEjson(args.get(2));
        if (!(converted instanceof JsonArray array)) {
            throw new TypeErrorException("db.bulkSave expects an array of documents");
        }
        final var documents = new ArrayList<JsonObject>();
        for (final var element : array) {
            if (!(element instanceof JsonObject document)) {
                throw new TypeErrorException("db.bulkSave expects an array of documents");
            }
            documents.add(document);
        }
        return outcome(database.bulkSave(arg(args, 0), arg(args, 1), documents));
    }

    private static JsValue outcome(BulkSaveOutcome result) {
        final var object = new JsObject();
        object.set("inserted", toStringArray(result.inserted()));
        object.set("updated", toStringArray(result.updated()));
        return object;
    }

    // The scoped-callback form is what makes a script transaction safe: a non-async, non-generator
    // body cannot contain `await`, so the transaction can never hop off the thread owning its locks.
    // The Java catch is outside the interpreter, so even a ScriptAbortException (not catchable by user
    // `try/catch`, skips `finally`) still rolls back and releases them.
    private static JsValue transaction(DatabaseAccess database, InterpreterOps ops, List<JsValue> args) {
        final var callback = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        requireSynchronousCallable(callback);
        database.beginTransaction();
        JsValue result;
        try {
            result = ops.call(callback, JsUndefined.getInstance(), List.of());
            rejectThenable(result);
        } catch (RuntimeException e) {
            database.rollbackTransaction();
            throw e;
        }
        database.commitTransaction();
        return result;
    }

    private static void requireSynchronousCallable(JsValue callback) {
        if (callback instanceof JsFunction function) {
            if (function.isAsync() || function.isGenerator()) {
                throw new TypeErrorException("db.transaction callback must be a synchronous function");
            }
            return;
        }
        if (!(callback instanceof JsNativeFunction)) {
            throw new TypeErrorException("db.transaction callback is not a function");
        }
    }

    private static void rejectThenable(JsValue result) {
        if (result instanceof JsPromise) {
            throw new TypeErrorException("db.transaction callback must not return a promise");
        }
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
