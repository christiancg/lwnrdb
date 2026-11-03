package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.RangeBounds;

final class ScanPreference {
    static final int MIN_ROWS = 4096;
    private static final int ALWAYS_INDEX = 100;
    private static final Configuration configuration = Configuration.getInstance();
    private static final Cache cache = IocContainer.get(Cache.class);

    private ScanPreference() {
    }

    static boolean tooBroad(long matched, String dbName, String collName) {
        final var percent = percent();
        if (percent >= ALWAYS_INDEX || matched * ALWAYS_INDEX <= (long) percent * MIN_ROWS) {
            return false;
        }
        final long rows = cache.pkIndexSize(dbName, collName);
        return rows >= MIN_ROWS && matched * ALWAYS_INDEX > percent * rows;
    }

    static boolean prefersScan(FieldOperator operator, String dbName, String collName, PipelineContext context)
            throws IOException {
        final var fieldName = operator.getField();
        if (percent() >= ALWAYS_INDEX || JsonUtils.isPrimaryKeyPath(fieldName)
                || cache.hasNoIndex(dbName, collName, fieldName)) {
            return false;
        }
        final var matched = countedMatches(operator, dbName, collName);
        if (matched.isEmpty() || !tooBroad(matched.getAsLong(), dbName, collName)) {
            return false;
        }
        context.indexUsed(fieldName, AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        return true;
    }

    static boolean indexFetchNotWorthIt(String dbName, String collName) {
        return groupByPrefersScan(dbName, collName) && !cache.holdsWholeCollection(dbName, collName);
    }

    static boolean groupByPrefersScan(String dbName, String collName) {
        return percent() < ALWAYS_INDEX && cache.pkIndexSize(dbName, collName) >= MIN_ROWS;
    }

    private static OptionalLong countedMatches(FieldOperator operator, String dbName, String collName)
            throws IOException {
        final var fieldName = operator.getField();
        return switch (operator.getFieldOperatorType()) {
            case EQUALS -> {
                final var value = CountOperatorHelper.scalarOperand(operator.getValue());
                yield value == null ? OptionalLong.empty() : cache.countEquals(dbName, collName, fieldName, value);
            }
            case NOT_EQUALS -> complementOf(scalarOperands(List.of(operator.getValue())), dbName, collName, fieldName);
            case NOT_IN -> operator.getValue() instanceof JsonArray operands
                    ? complementOf(scalarOperands(operands.asList()), dbName, collName, fieldName)
                    : OptionalLong.empty();
            default -> RangeBounds.isNumericRange(operator)
                    ? cache.countInRange(dbName, collName, RangeBounds.fromLeaves(fieldName, List.of(operator)))
                    : OptionalLong.empty();
        };
    }

    private static List<Object> scalarOperands(List<JsonBaseElement> operands) {
        final var values = new ArrayList<>(operands.size());
        for (final var operand : operands) {
            final var value = CountOperatorHelper.scalarOperand(operand);
            if (value == null) {
                return null;
            }
            values.add(value);
        }
        return values.isEmpty() ? null : values;
    }

    private static OptionalLong complementOf(List<Object> operands, String dbName, String collName, String fieldName)
            throws IOException {
        if (operands == null) {
            return OptionalLong.empty();
        }
        final var matched = cache.countEqualsAny(dbName, collName, fieldName, operands);
        return matched.isEmpty()
                ? matched
                : OptionalLong.of(Math.max(0L, cache.pkIndexSize(dbName, collName) - matched.getAsLong()));
    }

    private static int percent() {
        return configuration.intValue(ConfigKey.INDEX_SCAN_FALLBACK_PERCENT);
    }
}
