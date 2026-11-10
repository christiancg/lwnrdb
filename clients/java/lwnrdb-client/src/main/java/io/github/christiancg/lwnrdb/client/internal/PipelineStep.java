package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.MapOperation;
import java.util.List;

public sealed interface PipelineStep {
    record FilterStep(Filter filter) implements PipelineStep {
    }

    record MapStep(List<MapOperation> operations) implements PipelineStep {
    }

    record GroupByStep(String field) implements PipelineStep {
    }

    record JoinStep(String collection, String localField, String remoteField, String asField) implements PipelineStep {
    }

    record CountStep() implements PipelineStep {
    }

    record DistinctStep(String field) implements PipelineStep {
    }

    record LimitStep(int limit) implements PipelineStep {
    }

    record SkipStep(int skip) implements PipelineStep {
    }

    record SortStep(String field, boolean ascending) implements PipelineStep {
    }

    record ReduceStep(String script, boolean hasInitialValue, Object initialValue,
            String resultField) implements PipelineStep {
    }
}
