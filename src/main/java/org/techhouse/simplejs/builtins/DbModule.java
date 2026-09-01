package org.techhouse.simplejs.builtins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.BulkSaveOutcome;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
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

    public static JsObject create(DatabaseAccess database, InterpreterOps ops, Intrinsics intrinsics,
            ResourceLimits limits) {
        final var db = new JsObject();
        db.set("findById", new JsNativeFunction("findById", (_, args) -> findById(database, ops, args)));
        db.set("aggregate", new JsNativeFunction("aggregate", (_, args) -> aggregate(database, ops, args)));
        db.set("save", new JsNativeFunction("save", (_, args) -> save(database, ops, args)));
        db.set("bulkSave", new JsNativeFunction("bulkSave", (_, args) -> bulkSave(database, ops, args)));
        db.set("cursor", new JsNativeFunction("cursor", (_, args) -> cursor(database, ops, intrinsics, limits, args)));
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

    private static JsValue findById(DatabaseAccess database, InterpreterOps ops, List<JsValue> args) {
        final var document = database.findById(arg(args, 0), arg(args, 1), arg(args, 2));
        InterpreterOps.charge(ops, EJsonInterop.estimatedBytes(document));
        return EJsonInterop.fromEjson(document);
    }

    // Charged per element inside the loop, not once at the end: the point is to abort a runaway result
    // partway rather than after the whole JS copy already exists. What the script passed in is never
    // charged - it was charged when the script allocated it.
    private static JsValue aggregate(DatabaseAccess database, InterpreterOps ops, List<JsValue> args) {
        final var pipeline = (JsonArray) EJsonInterop.toHostEjson(args.get(2));
        final var results = database.aggregate(arg(args, 0), arg(args, 1), pipeline);
        final var array = new JsArray();
        for (final var result : results) {
            InterpreterOps.charge(ops, EJsonInterop.estimatedBytes(result));
            array.push(EJsonInterop.fromEjson(result));
        }
        return array;
    }

    private static JsValue save(DatabaseAccess database, InterpreterOps ops, List<JsValue> args) {
        final var document = (JsonObject) EJsonInterop.toHostEjson(args.get(2));
        final var saved = database.save(arg(args, 0), arg(args, 1), document);
        InterpreterOps.charge(ops, EJsonInterop.estimatedBytes(saved));
        return EJsonInterop.fromEjson(saved);
    }

    private static JsValue bulkSave(DatabaseAccess database, InterpreterOps ops, List<JsValue> args) {
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
        final var result = database.bulkSave(arg(args, 0), arg(args, 1), documents);
        InterpreterOps.chargeElements(ops, (long) result.inserted().size() + result.updated().size());
        return outcome(result);
    }

    // A paged cursor over the live collection, not a snapshot: each batch is an ordinary AGGREGATE with
    // SKIP/LIMIT appended, so it is authorized, schema-checked and cluster-routed like a hand-written
    // db.aggregate, and a concurrent write between two batches is observable.
    private static JsValue cursor(DatabaseAccess database, InterpreterOps ops, Intrinsics intrinsics,
            ResourceLimits limits, List<JsValue> args) {
        final var converted = args.size() > 2 ? EJsonInterop.toHostEjson(args.get(2)) : null;
        if (!(converted instanceof JsonArray steps)) {
            throw new TypeErrorException("db.cursor expects an array of aggregation steps");
        }
        final var iterator = JsIterators
                .of(new BatchIterator(database, ops, arg(args, 0), arg(args, 1), steps, batchSize(args, limits)));
        return JsIterators.linkPrototype(iterator, intrinsics == null ? null : intrinsics.dbCursorProto());
    }

    private static int batchSize(List<JsValue> args, ResourceLimits limits) {
        final var max = limits == null || limits.cursorMaxBatchSize() < 1
                ? ResourceLimits.DEFAULT_CURSOR_MAX_BATCH_SIZE
                : limits.cursorMaxBatchSize();
        final var options = args.size() > 3 ? args.get(3) : JsUndefined.getInstance();
        if (!(options instanceof JsObject object) || !object.has("batchSize")) {
            final var configured = limits == null || limits.cursorBatchSize() < 1
                    ? ResourceLimits.DEFAULT_CURSOR_BATCH_SIZE
                    : limits.cursorBatchSize();
            return Math.min(configured, max);
        }
        final var requested = JsCoercion.toNumber(object.get("batchSize"));
        if (Double.isNaN(requested) || requested < 1) {
            throw new RangeErrorException("db.cursor batchSize must be a number greater than or equal to 1");
        }
        return (int) Math.min(requested, max);
    }

    private static final class BatchIterator implements Iterator<JsValue> {
        private final DatabaseAccess database;
        private final InterpreterOps ops;
        private final String dbName;
        private final String collName;
        private final JsonArray steps;
        private final int batchSize;
        private final Deque<JsValue> buffer = new ArrayDeque<>();
        private int skip;
        private boolean exhausted;
        private long batchBytes;

        private BatchIterator(DatabaseAccess database, InterpreterOps ops, String dbName, String collName,
                JsonArray steps, int batchSize) {
            this.database = database;
            this.ops = ops;
            this.dbName = dbName;
            this.collName = collName;
            this.steps = steps;
            this.batchSize = batchSize;
        }

        @Override
        public boolean hasNext() {
            if (buffer.isEmpty() && !exhausted) {
                fetch();
            }
            return !buffer.isEmpty();
        }

        @Override
        public JsValue next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return buffer.removeFirst();
        }

        // The previous batch is drained before the next is fetched, so its charge is credited back
        // first: streaming a collection costs one batch of the memory budget, not all of it.
        private void fetch() {
            InterpreterOps.release(ops, batchBytes);
            batchBytes = 0;
            final var results = database.aggregate(dbName, collName, paged());
            for (final var result : results) {
                final var bytes = EJsonInterop.estimatedBytes(result);
                InterpreterOps.charge(ops, bytes);
                batchBytes += bytes;
                buffer.addLast(EJsonInterop.fromEjson(result));
            }
            skip += results.size();
            exhausted = results.size() < batchSize;
        }

        private JsonArray paged() {
            final var pipeline = new JsonArray();
            pipeline.addAll(steps);
            pipeline.add(step("SKIP", "skip", skip));
            pipeline.add(step("LIMIT", "limit", batchSize));
            return pipeline;
        }

        private static JsonObject step(String type, String field, int value) {
            final var step = new JsonObject();
            step.add("type", new JsonString(type));
            step.add(field, new JsonNumber(value));
            return step;
        }
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
