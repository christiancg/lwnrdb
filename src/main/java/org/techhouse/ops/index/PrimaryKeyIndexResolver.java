package org.techhouse.ops.index;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;

public final class PrimaryKeyIndexResolver {
    private static final Cache cache = IocContainer.get(Cache.class);

    private PrimaryKeyIndexResolver() {
    }

    public static Set<String> resolve(FieldOperator operator, String dbName, String collName) throws IOException {
        final var operand = operator.getValue();
        final var resolved = switch (operator.getFieldOperatorType()) {
            case EQUALS -> idsEqualTo(operand, dbName, collName);
            case NOT_EQUALS -> isNotPlainString(operand)
                    ? null
                    : complementOf(idsEqualTo(operand, dbName, collName), dbName, collName);
            case IN -> idsIn(operand, dbName, collName);
            case NOT_IN -> complementOf(idsIn(operand, dbName, collName), dbName, collName);
            case CONTAINS -> idsContaining(operand, dbName, collName);
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS -> null;
        };
        if (resolved != null) {
            recordAnalyzeIndexUse(dbName, collName);
        }
        return resolved;
    }

    private static Set<String> idsEqualTo(JsonBaseElement operand, String dbName, String collName) throws IOException {
        if (isNotPlainString(operand)) {
            return Set.of();
        }
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        return Collections.binarySearch(pkIndex, operand.asJsonString().getValue()) >= 0
                ? Set.of(operand.asJsonString().getValue())
                : Set.of();
    }

    private static Set<String> idsIn(JsonBaseElement operand, String dbName, String collName) throws IOException {
        if (!(operand instanceof JsonArray operands)) {
            return Set.of();
        }
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var matched = new HashSet<String>();
        for (final var candidate : operands.asList()) {
            if (isNotPlainString(candidate)) {
                continue;
            }
            final var id = candidate.asJsonString().getValue();
            if (Collections.binarySearch(pkIndex, id) >= 0) {
                matched.add(id);
            }
        }
        return matched;
    }

    private static Set<String> idsContaining(JsonBaseElement operand, String dbName, String collName)
            throws IOException {
        if (isNotPlainString(operand)) {
            return Set.of();
        }
        final var needle = operand.asJsonString().getValue();
        final var matched = new HashSet<String>();
        for (final var entry : cache.getPkIndexAndLoadIfNecessary(dbName, collName)) {
            if (entry.getValue().contains(needle)) {
                matched.add(entry.getValue());
            }
        }
        return matched;
    }

    private static Set<String> complementOf(Set<String> matched, String dbName, String collName) throws IOException {
        final List<PkIndexEntry> pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var complement = new HashSet<String>();
        for (final var entry : pkIndex) {
            if (!matched.contains(entry.getValue())) {
                complement.add(entry.getValue());
            }
        }
        return complement;
    }

    private static boolean isNotPlainString(JsonBaseElement element) {
        return !(element instanceof JsonString) || element.isJsonCustom();
    }

    private static void recordAnalyzeIndexUse(String dbName, String collName) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addIndexUsed(Globals.PK_FIELD);
            analyzeContext.addLock(Cache.getCollectionIdentifier(dbName, collName));
        }
    }
}
