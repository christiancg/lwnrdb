package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
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
        return percent() < ALWAYS_INDEX && cache.pkIndexSize(dbName, collName) >= MIN_ROWS
                && !cache.holdsWholeCollection(dbName, collName);
    }

    private static OptionalLong countedMatches(FieldOperator operator, String dbName, String collName)
            throws IOException {
        final var fieldName = operator.getField();
        if (operator.getFieldOperatorType() == FieldOperatorType.EQUALS) {
            final var value = CountOperatorHelper.scalarOperand(operator.getValue());
            return value == null ? OptionalLong.empty() : cache.countEquals(dbName, collName, fieldName, value);
        }
        if (RangeBounds.isNumericRange(operator)) {
            return cache.countInRange(dbName, collName, RangeBounds.fromLeaves(fieldName, List.of(operator)));
        }
        return OptionalLong.empty();
    }

    private static int percent() {
        return configuration.intValue(ConfigKey.INDEX_SCAN_FALLBACK_PERCENT);
    }
}
