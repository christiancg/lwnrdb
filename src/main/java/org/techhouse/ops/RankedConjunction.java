package org.techhouse.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;

final class RankedConjunction {
    private RankedConjunction() {
    }

    static Stream<JsonObject> filter(ConjunctionOperator operator, Stream<JsonObject> input, String dbName,
            String collName, PipelineContext context) throws IOException {
        if (input != null) {
            final List<JsonObject> buffered;
            try (var rows = context.watch(input)) {
                buffered = rows.toList();
            }
            final var test = compiled(operator, context,
                    ranking -> bufferedMembership(ranking, buffered, dbName, collName, context));
            return context.watch(buffered.stream()).filter(test);
        }
        final var test = compiled(operator, context, ranking -> sourceMembership(ranking, dbName, collName, context));
        return context.source(null, dbName, collName).filter(test);
    }

    private static Predicate<JsonObject> compiled(ConjunctionOperator operator, PipelineContext context,
            OperatorPredicate.Ranked ranked) throws IOException {
        try {
            return OperatorPredicate.of(operator, context, ranked);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Predicate<JsonObject> bufferedMembership(CustomOperator ranking, List<JsonObject> buffered,
            String dbName, String collName, PipelineContext context) {
        final Set<JsonObject> ranked = Collections.newSetFromMap(new IdentityHashMap<>());
        try (var rows = FilterOperatorHelper.rankedRows(ranking, context.watch(buffered.stream()), dbName, collName,
                context)) {
            rows.forEach(ranked::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ranked::contains;
    }

    private static Predicate<JsonObject> sourceMembership(CustomOperator ranking, String dbName, String collName,
            PipelineContext context) {
        final var keys = new HashSet<>();
        try (var rows = FilterOperatorHelper.rankedRows(ranking, null, dbName, collName, context)) {
            rows.forEach(row -> keys.add(OperatorPredicate.keyOf(row)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return row -> keys.contains(OperatorPredicate.keyOf(row));
    }
}
