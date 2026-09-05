package org.techhouse.ops;

import java.io.IOException;
import java.util.stream.Stream;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.values.EJsonInterop;

// Folds the whole upstream stream into one document, exactly as COUNT collapses it to {count:N}: a step
// after a REDUCE is legal and operates on the single document it emits.
public final class ReduceOperatorHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();

    private ReduceOperatorHelper() {
    }

    public static Stream<JsonObject> processReduceStep(ReduceAggregationStep step, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineScriptContext context) throws IOException {
        final var stream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var callable = context.callableFor(step.getScript());
        var accumulator = step.getInitialValue() == null ? JsonNull.INSTANCE : step.getInitialValue();
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
        return Stream.of(resultDocument(step, accumulator));
    }

    // A fold that accumulates every document into one value can outgrow anything the per-document
    // charge sees, so the value that actually leaves the pipeline is measured before it is emitted.
    private static JsonObject resultDocument(ReduceAggregationStep step, JsonBaseElement accumulator) {
        final var max = configuration.getScriptMaxResultBytes();
        if (max >= 0) {
            final var size = EJsonInterop.estimatedBytes(accumulator);
            if (size > max) {
                throw new ScriptCallableException("ScriptResultTooLargeError",
                        "Reduced value of about " + size + " bytes exceeds the maximum of " + max + " bytes");
            }
        }
        final var result = new JsonObject();
        result.add(step.getResultField(), accumulator);
        return result;
    }
}
