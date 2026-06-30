package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.analyze.AnalyzeResult;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;

/**
 * Assembles the {@link AnalyzeResult} for an analyzed aggregation from the metrics gathered in the
 * {@link AnalyzeContext} plus a static inspection of the requested pipeline. The timing fields are
 * left at zero here; {@code MessageProcessor} fills them in around the processing call.
 */
public final class AnalyzeHelper {
    private AnalyzeHelper() {
    }

    public static AnalyzeResult build(AggregateRequest request, AnalyzeContext context) {
        final var result = new AnalyzeResult();
        final var indexesUsed = new ArrayList<>(context.getIndexesUsed());
        indexesUsed.sort(String::compareTo);
        final var indexUsed = !indexesUsed.isEmpty();
        result.setIndexUsed(indexUsed);
        result.setIndexesUsed(indexesUsed);
        result.setDocumentsScanned(context.getDocumentsScanned());
        result.setLocksAcquired(new ArrayList<>(context.getLocksAcquired()));
        result.setSuggestions(buildSuggestions(request, indexUsed));
        return result;
    }

    private static List<String> buildSuggestions(AggregateRequest request, boolean indexUsed) {
        final var suggestions = new ArrayList<String>();
        final var steps = request.getAggregationSteps();
        if (steps == null) {
            return suggestions;
        }
        if (!indexUsed) {
            final var candidateFields = collectCandidateFields(steps);
            if (candidateFields.isEmpty()) {
                suggestions.add("No index was used for this query.");
            } else {
                suggestions.add("No index was used for this query. Consider creating an index on: "
                        + String.join(", ", candidateFields) + ".");
            }
        }
        for (var i = 0; i < steps.size(); i++) {
            if (i > 0 && steps.get(i) instanceof FilterAggregationStep filterStep) {
                final var fields = new LinkedHashSet<String>();
                collectFilterFields(filterStep.getOperator(), fields);
                final var fieldLabel = fields.isEmpty() ? "" : " on field '" + String.join(", ", fields) + "'";
                suggestions.add("FILTER step" + fieldLabel + " at step " + (i + 1)
                        + " is not the first step; move it to the top of the pipeline so it can use an index"
                        + " and reduce documents scanned.");
            }
        }
        return suggestions;
    }

    // Collects, in pipeline order, the fields any index-capable step references, so the "no index"
    // suggestion can name concrete candidates to index.
    private static Collection<String> collectCandidateFields(List<BaseAggregationStep> steps) {
        final var fields = new LinkedHashSet<String>();
        for (var step : steps) {
            switch (step) {
                case FilterAggregationStep f -> collectFilterFields(f.getOperator(), fields);
                case SortAggregationStep s -> addIfPresent(fields, s.getFieldName());
                case GroupByAggregationStep g -> addIfPresent(fields, g.getFieldName());
                case JoinAggregationStep j -> addIfPresent(fields, j.getRemoteField());
                case DistinctAggregationStep d -> addIfPresent(fields, d.getFieldName());
                default -> {
                }
            }
        }
        return fields;
    }

    private static void collectFilterFields(BaseOperator operator, Collection<String> fields) {
        if (operator instanceof FieldOperator fieldOperator) {
            addIfPresent(fields, fieldOperator.getField());
        } else if (operator instanceof ConjunctionOperator conjunctionOperator
                && conjunctionOperator.getOperators() != null) {
            for (var child : conjunctionOperator.getOperators()) {
                collectFilterFields(child, fields);
            }
        }
    }

    private static void addIfPresent(Collection<String> fields, String field) {
        if (field != null && !field.isBlank()) {
            fields.add(field);
        }
    }
}
