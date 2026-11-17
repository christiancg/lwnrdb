package io.github.christiancg.lwnrdb.client.query;

import io.github.christiancg.lwnrdb.client.AnalyzeResult;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.internal.PipelineStep;
import io.github.christiancg.lwnrdb.client.internal.QueryTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Query<T> {
    private static final String COUNT_FIELD = "count";

    private final QueryTarget<T> target;
    private final List<PipelineStep> steps;
    private final boolean dirtyRead;

    public Query(QueryTarget<T> target) {
        this(target, List.of(), false);
    }

    private Query(QueryTarget<T> target, List<PipelineStep> steps, boolean dirtyRead) {
        this.target = Objects.requireNonNull(target, "target");
        this.steps = steps;
        this.dirtyRead = dirtyRead;
    }

    public Query<T> filter(Filter filter) {
        return then(new PipelineStep.FilterStep(Objects.requireNonNull(filter, "filter")));
    }

    public Query<T> map(MapOperation... operations) {
        if (operations.length == 0) {
            throw new IllegalArgumentException("map needs at least one operation");
        }
        return then(new PipelineStep.MapStep(List.of(operations)));
    }

    public Query<T> groupBy(String field) {
        return then(new PipelineStep.GroupByStep(Objects.requireNonNull(field, "field")));
    }

    public Query<T> join(String collection, String localField, String remoteField, String asField) {
        return then(new PipelineStep.JoinStep(Objects.requireNonNull(collection, "collection"),
                Objects.requireNonNull(localField, "localField"), Objects.requireNonNull(remoteField, "remoteField"),
                Objects.requireNonNull(asField, "asField")));
    }

    public Query<T> distinct() {
        return then(new PipelineStep.DistinctStep(null));
    }

    public Query<T> distinct(String field) {
        return then(new PipelineStep.DistinctStep(Objects.requireNonNull(field, "field")));
    }

    public Query<T> skip(int skip) {
        if (skip < 0) {
            throw new IllegalArgumentException("skip must not be negative: " + skip);
        }
        return then(new PipelineStep.SkipStep(skip));
    }

    public Query<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return then(new PipelineStep.LimitStep(limit));
    }

    public Query<T> sort(String field, Order order) {
        return then(new PipelineStep.SortStep(Objects.requireNonNull(field, "field"), order == Order.ASC));
    }

    public Query<T> reduce(String script) {
        return then(new PipelineStep.ReduceStep(Objects.requireNonNull(script, "script"), false, null, null));
    }

    public Query<T> reduce(String script, Object initialValue, String resultField) {
        return then(
                new PipelineStep.ReduceStep(Objects.requireNonNull(script, "script"), true, initialValue, resultField));
    }

    public Query<T> dirtyRead() {
        return new Query<>(target, steps, true);
    }

    public List<T> toList() {
        final var codec = target.codec();
        return toDocuments().stream().map(codec::decode).toList();
    }

    public List<Document> toDocuments() {
        return target.aggregate(steps, dirtyRead);
    }

    public Optional<T> first() {
        final var codec = target.codec();
        return limit(1).toDocuments().stream().findFirst().map(codec::decode);
    }

    public long count() {
        final var rows = then(new PipelineStep.CountStep()).toDocuments();
        return rows.isEmpty() ? 0 : rows.get(0).getLong(COUNT_FIELD).orElse(0);
    }

    public AnalyzeResult analyze() {
        return target.analyze(steps, dirtyRead);
    }

    public List<PipelineStep> steps() {
        return steps;
    }

    public boolean isDirtyRead() {
        return dirtyRead;
    }

    private Query<T> then(PipelineStep step) {
        final var next = new ArrayList<PipelineStep>(steps.size() + 1);
        next.addAll(steps);
        next.add(step);
        return new Query<>(target, List.copyOf(next), dirtyRead);
    }
}
