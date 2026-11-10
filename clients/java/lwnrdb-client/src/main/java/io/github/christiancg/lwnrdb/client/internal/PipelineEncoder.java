package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.query.ArrayOperation;
import io.github.christiancg.lwnrdb.client.query.CastOperation;
import io.github.christiancg.lwnrdb.client.query.ConjunctionFilter;
import io.github.christiancg.lwnrdb.client.query.DistanceFilter;
import io.github.christiancg.lwnrdb.client.query.FieldFilter;
import io.github.christiancg.lwnrdb.client.query.FieldFunction;
import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.Literal;
import io.github.christiancg.lwnrdb.client.query.MapOperation;
import io.github.christiancg.lwnrdb.client.query.MidOperation;
import io.github.christiancg.lwnrdb.client.query.NearestFilter;
import io.github.christiancg.lwnrdb.client.query.ScriptFilter;
import io.github.christiancg.lwnrdb.client.query.ScriptOperation;
import io.github.christiancg.lwnrdb.client.query.WithinFilter;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class PipelineEncoder {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String TYPE = "type";
    private static final String FIELD = "field";
    private static final String FIELD_NAME = "fieldName";
    private static final String VALUE = "value";
    private static final String CUSTOM_OPERATOR = "customOperatorName";

    private final Json json;

    public PipelineEncoder(Json json) {
        this.json = json;
    }

    public ArrayNode steps(List<PipelineStep> steps) {
        final var array = NODES.arrayNode();
        steps.forEach(step -> array.add(step(step)));
        return array;
    }

    public ObjectNode filter(Filter filter) {
        final var node = NODES.objectNode();
        if (filter instanceof FieldFilter field) {
            node.put("fieldOperatorType", field.operator().name()).put(FIELD, field.field()).set(VALUE,
                    value(field.value()));
        } else if (filter instanceof ConjunctionFilter conjunction) {
            final var operands = node.put("conjunctionType", conjunction.conjunction().name()).putArray("operators");
            conjunction.operands().forEach(operand -> operands.add(filter(operand)));
        } else if (filter instanceof DistanceFilter distance) {
            node.put(CUSTOM_OPERATOR, "distance").put(FIELD, distance.field())
                    .put(VALUE, CustomValues.format(distance.target())).put("comparator", distance.comparator().name())
                    .set("distance", number(distance.metres()));
        } else if (filter instanceof WithinFilter within) {
            final var polygon = node.put(CUSTOM_OPERATOR, "within").put(FIELD, within.field()).putArray("polygon");
            within.polygon().forEach(point -> polygon.add(CustomValues.format(point)));
        } else if (filter instanceof NearestFilter nearest) {
            node.put(CUSTOM_OPERATOR, "nearest").put(FIELD, nearest.field())
                    .put(VALUE, CustomValues.format(nearest.query())).put("k", nearest.k());
            if (nearest.isExact()) {
                node.put("exact", true);
            }
        } else {
            node.put("script", ((ScriptFilter) filter).source());
        }
        return node;
    }

    private ObjectNode step(PipelineStep step) {
        final var node = NODES.objectNode();
        if (step instanceof PipelineStep.FilterStep filter) {
            node.put(TYPE, "FILTER").set("operator", filter(filter.filter()));
        } else if (step instanceof PipelineStep.MapStep map) {
            final var operators = node.put(TYPE, "MAP").putArray("operators");
            map.operations().forEach(operation -> operators.add(mapOperation(operation)));
        } else if (step instanceof PipelineStep.GroupByStep groupBy) {
            node.put(TYPE, "GROUP_BY").put(FIELD_NAME, groupBy.field());
        } else if (step instanceof PipelineStep.JoinStep join) {
            node.put(TYPE, "JOIN").put("joinCollection", join.collection()).put("localField", join.localField())
                    .put("remoteField", join.remoteField()).put("asField", join.asField());
        } else if (step instanceof PipelineStep.CountStep) {
            node.put(TYPE, "COUNT");
        } else if (step instanceof PipelineStep.DistinctStep distinct) {
            node.put(TYPE, "DISTINCT");
            if (distinct.field() != null) {
                node.put(FIELD_NAME, distinct.field());
            }
        } else if (step instanceof PipelineStep.LimitStep limit) {
            node.put(TYPE, "LIMIT").put("limit", limit.limit());
        } else if (step instanceof PipelineStep.SkipStep skip) {
            node.put(TYPE, "SKIP").put("skip", skip.skip());
        } else if (step instanceof PipelineStep.SortStep sort) {
            node.put(TYPE, "SORT").put(FIELD_NAME, sort.field()).put("ascending", sort.ascending());
        } else {
            reduce((PipelineStep.ReduceStep) step, node.put(TYPE, "REDUCE"));
        }
        return node;
    }

    private void reduce(PipelineStep.ReduceStep reduce, ObjectNode node) {
        node.put("script", reduce.script());
        if (reduce.hasInitialValue()) {
            node.set("initialValue", value(reduce.initialValue()));
        }
        if (reduce.resultField() != null) {
            node.put("resultField", reduce.resultField());
        }
    }

    private ObjectNode mapOperation(MapOperation operation) {
        final var node = NODES.objectNode().put(FIELD_NAME, operation.field());
        operation.operation().ifPresent(mid -> node.set("operator", midOperation(mid)));
        operation.condition().ifPresent(condition -> node.set("condition", filter(condition)));
        return node;
    }

    private ObjectNode midOperation(MidOperation operation) {
        final var node = NODES.objectNode();
        if (operation instanceof ArrayOperation array) {
            final var operands = node.put(TYPE, array.operator().name()).putArray("operands");
            array.operands().forEach(operand -> operands.add(operand(operand)));
        } else if (operation instanceof FieldFunction function) {
            node.put(TYPE, function.function().name()).put("operand", function.field());
        } else if (operation instanceof CastOperation cast) {
            node.put(TYPE, "CAST").put(FIELD_NAME, cast.field()).put("toType", cast.type().name());
            if (cast.customType() != null) {
                node.put("customTypeName", cast.customType().wireName());
            }
        } else {
            node.put(TYPE, "SCRIPT").put("script", ((ScriptOperation) operation).source());
        }
        return node;
    }

    private JsonNode operand(Object operand) {
        if (operand instanceof Literal literal) {
            return NODES.stringNode(Literal.WIRE_PREFIX + literal.text());
        }
        if (operand instanceof String field) {
            return NODES.stringNode(field);
        }
        return value(operand);
    }

    public JsonNode value(Object value) {
        return value == null ? NODES.nullNode() : json.toNode(value);
    }

    private static JsonNode number(double value) {
        return value == Math.rint(value) && Math.abs(value) < NumberGuard.MAX_EXACT_INTEGER
                ? NODES.numberNode((long) value)
                : NODES.numberNode(value);
    }
}
