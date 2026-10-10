package org.techhouse.ops;

import static org.techhouse.simplejs.host.ScriptErrorNames.RESULT_TOO_LARGE;

import java.io.IOException;
import java.util.Comparator;
import java.util.stream.Stream;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.utils.JsonUtils;

public final class ReduceOperatorHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Comparator<JsonObject> FOLD_ORDER = Comparator
            .comparing(ReduceOperatorHelper::primaryKeyOf, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(JsonUtils::canonicalize).thenComparing(ReduceOperatorHelper::wireTextOf);

    private ReduceOperatorHelper() {
    }

    public static Stream<JsonObject> inFoldOrder(Stream<JsonObject> rows) {
        try (rows) {
            return rows.sorted(FOLD_ORDER).toList().stream();
        }
    }

    private static String primaryKeyOf(JsonObject row) {
        return row.get(Globals.PK_FIELD) instanceof JsonString id ? id.getValue() : null;
    }

    private static String wireTextOf(JsonObject row) {
        return eJson.toJson(row);
    }

    public static Stream<JsonObject> processReduceStep(ReduceAggregationStep step, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineScriptContext context) throws IOException {
        final var callable = context.callableFor(step.getScript());
        var accumulator = step.getInitialValue() == null ? JsonNull.INSTANCE : step.getInitialValue();
        try (var stream = resultStream != null
                ? resultStream
                : cache.streamCollectionInScanOrder(dbName, collName).map(DbEntry::getData)) {
            for (final var document : (Iterable<JsonObject>) stream::iterator) {
                final var analyze = AnalyzeContext.current();
                final var start = analyze == null ? 0 : System.nanoTime();
                accumulator = callable.apply(accumulator, document);
                if (analyze != null) {
                    analyze.recordScriptInvocation(System.nanoTime() - start);
                }
                if (accumulator == null) {
                    accumulator = JsonNull.INSTANCE;
                }
            }
        }
        return Stream.of(resultDocument(step, accumulator));
    }

    private static JsonObject resultDocument(ReduceAggregationStep step, JsonBaseElement accumulator) {
        final var max = configuration.getScriptMaxResultBytes();
        if (max >= 0) {
            final var size = EJsonInterop.estimatedBytes(accumulator);
            if (size > max) {
                throw new ScriptCallableException(RESULT_TOO_LARGE,
                        "Reduced value of about " + size + " bytes exceeds the maximum of " + max + " bytes");
            }
        }
        final var result = new JsonObject();
        JsonUtils.setPath(result, step.getResultField(), accumulator);
        return result;
    }
}
