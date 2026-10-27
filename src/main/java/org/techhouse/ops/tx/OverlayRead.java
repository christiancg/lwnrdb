package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.OperatorPredicate;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

public final class OverlayRead {
    private static final Cache cache = IocContainer.get(Cache.class);

    private OverlayRead() {
    }

    public static List<JsonObject> runPipeline(AggregateRequest request, Transaction transaction,
            PipelineContext context) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var overlay = transaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName));
        final var steps = request.getAggregationSteps();
        final var first = steps == null || steps.isEmpty() ? null : steps.getFirst();
        if (first instanceof FilterAggregationStep filter && !OperatorPredicate.holdsRanking(filter.getOperator())) {
            final var operator = filter.getOperator();
            final var committed = FilterOperatorHelper.processOperator(operator, null, dbName, collName, context);
            final var matching = bufferedMatches(overlay, operator, dbName, collName, context);
            return AggregationOperationHelper.processAggregation(request,
                    reconcile(overlay, committed, matching::contains), 1, transaction, context);
        }
        final var committed = startsWithReduce(request)
                ? context.scan(cache.streamCollectionInScanOrder(dbName, collName)).map(DbEntry::getData)
                : context.source(null, dbName, collName);
        return AggregationOperationHelper.processAggregation(request, reconcile(overlay, committed, _ -> true),
                transaction, context);
    }

    public static Stream<JsonObject> reconcile(Map<String, JsonObject> overlay, Stream<JsonObject> committed,
            Predicate<String> bufferedMatches) {
        if (overlay == null || overlay.isEmpty()) {
            return committed;
        }
        final var seen = new HashSet<String>();
        final Stream<JsonObject> head = committed.mapMulti((JsonObject document, Consumer<JsonObject> sink) -> {
            final var id = idOf(document);
            if (id == null || !overlay.containsKey(id)) {
                sink.accept(document);
                return;
            }
            seen.add(id);
            final var buffered = overlay.get(id);
            if (!Transaction.isTombstone(buffered) && bufferedMatches.test(id)) {
                sink.accept(buffered);
            }
        });
        final Stream<JsonObject> tail = Stream.of(overlay)
                .flatMap(entries -> entries
                        .entrySet().stream().filter(entry -> !Transaction.isTombstone(entry.getValue())
                                && !seen.contains(entry.getKey()) && bufferedMatches.test(entry.getKey()))
                        .map(Map.Entry::getValue));
        return Stream.concat(head, tail);
    }

    public static List<JsonObject> withBuffered(Map<String, JsonObject> overlay, List<JsonObject> committedMatches) {
        if (overlay == null || overlay.isEmpty()) {
            return committedMatches;
        }
        final var merged = new ArrayList<JsonObject>(committedMatches.size() + overlay.size());
        for (final var document : committedMatches) {
            final var id = idOf(document);
            if (id == null || !overlay.containsKey(id)) {
                merged.add(document);
            }
        }
        for (final var buffered : overlay.values()) {
            if (!Transaction.isTombstone(buffered)) {
                merged.add(buffered);
            }
        }
        return merged;
    }

    private static Set<String> bufferedMatches(Map<String, JsonObject> overlay, BaseOperator operator, String dbName,
            String collName, PipelineContext context) throws IOException {
        final var buffered = overlay.values().stream().filter(document -> !Transaction.isTombstone(document)).toList();
        context.scanned(buffered.size());
        final var matching = new HashSet<String>();
        try (var matches = FilterOperatorHelper.processOperator(operator, buffered.stream(), dbName, collName,
                context)) {
            matches.forEach(document -> matching.add(idOf(document)));
        }
        return matching;
    }

    private static boolean startsWithReduce(AggregateRequest request) {
        final var steps = request.getAggregationSteps();
        return steps != null && !steps.isEmpty() && steps.getFirst().getType() == AggregationStepType.REDUCE;
    }

    private static String idOf(JsonObject document) {
        final var id = document.get(Globals.PK_FIELD);
        return id != null && id.isJsonString() ? id.asJsonString().getValue() : null;
    }
}
