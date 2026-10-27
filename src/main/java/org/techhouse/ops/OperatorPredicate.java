package org.techhouse.ops;

import java.util.Set;
import java.util.function.Predicate;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;

public final class OperatorPredicate {
    interface Ranked {
        Predicate<JsonObject> membership(CustomOperator ranking);
    }

    private OperatorPredicate() {
    }

    static Predicate<JsonObject> of(BaseOperator operator, PipelineContext context) {
        return of(operator, context, null, Set.of());
    }

    static Predicate<JsonObject> of(BaseOperator operator, PipelineContext context, Set<String> mutableRoots) {
        return of(operator, context, null, mutableRoots);
    }

    static Predicate<JsonObject> of(BaseOperator operator, PipelineContext context, Ranked ranked) {
        return of(operator, context, ranked, Set.of());
    }

    private static Predicate<JsonObject> of(BaseOperator operator, PipelineContext context, Ranked ranked,
            Set<String> mutableRoots) {
        return switch (operator.getType()) {
            case FIELD -> field((FieldOperator) operator);
            case CUSTOM -> custom((CustomOperator) operator, ranked);
            case SCRIPT -> {
                final var script = (ScriptOperator) operator;
                yield row -> FilterOperatorHelper.testScript(script, row, mutableRoots, context);
            }
            case CONJUNCTION -> conjunction((ConjunctionOperator) operator, context, ranked, mutableRoots);
        };
    }

    public static boolean holdsRanking(BaseOperator operator) {
        return switch (operator) {
            case CustomOperator custom -> CustomTypeFactory.isRankingOperator(custom.getCustomOperatorName());
            case ConjunctionOperator conjunction ->
                conjunction.getOperators().stream().anyMatch(OperatorPredicate::holdsRanking);
            default -> false;
        };
    }

    static boolean holdsScript(BaseOperator operator) {
        return switch (operator) {
            case ScriptOperator _ -> true;
            case ConjunctionOperator conjunction ->
                conjunction.getOperators().stream().anyMatch(OperatorPredicate::holdsScript);
            default -> false;
        };
    }

    static Object keyOf(JsonObject row) {
        final var id = row.get(Globals.PK_FIELD);
        return id != null ? id : row;
    }

    private static Predicate<JsonObject> field(FieldOperator operator) {
        final var tester = FieldPredicateFactory.getTester(operator, operator.getFieldOperatorType());
        final var fieldName = operator.getField();
        return row -> tester.test(row, fieldName);
    }

    private static Predicate<JsonObject> custom(CustomOperator operator, Ranked ranked) {
        if (ranked != null && CustomTypeFactory.isRankingOperator(operator.getCustomOperatorName())) {
            return ranked.membership(operator);
        }
        final var tester = FilterOperatorHelper.getCustomTester(operator);
        final var fieldName = operator.getField();
        return row -> tester.test(row, fieldName);
    }

    private static Predicate<JsonObject> conjunction(ConjunctionOperator operator, PipelineContext context,
            Ranked ranked, Set<String> mutableRoots) {
        final var operands = operator.getOperators();
        final var legs = new ConjunctionTest(operator.getConjunctionType(), holdsScript(operator), operands.size());
        for (var i = 0; i < operands.size(); i++) {
            legs.legs[i] = of(operands.get(i), context, ranked, mutableRoots);
        }
        return legs;
    }

    private static final class ConjunctionTest implements Predicate<JsonObject> {
        private final ConjunctionOperatorType type;
        private final boolean everyLeg;
        private final Predicate<?>[] legs;

        private ConjunctionTest(ConjunctionOperatorType type, boolean everyLeg, int legCount) {
            this.type = type;
            this.everyLeg = everyLeg;
            this.legs = new Predicate<?>[legCount];
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean test(JsonObject row) {
            var matched = 0;
            for (var seen = 1; seen <= legs.length; seen++) {
                if (((Predicate<JsonObject>) legs[seen - 1]).test(row)) {
                    matched++;
                }
                if (!everyLeg && decided(matched, seen)) {
                    break;
                }
            }
            return switch (type) {
                case AND -> matched == legs.length;
                case OR -> matched > 0;
                case XOR -> matched == 1;
                case NOR -> matched == 0;
                case NAND -> matched != legs.length;
            };
        }

        private boolean decided(int matched, int seen) {
            return switch (type) {
                case AND, NAND -> matched < seen;
                case OR, NOR -> matched > 0;
                case XOR -> matched > 1;
            };
        }
    }
}
