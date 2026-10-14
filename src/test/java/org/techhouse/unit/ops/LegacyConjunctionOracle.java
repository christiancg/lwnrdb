package org.techhouse.unit.ops;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;

final class LegacyConjunctionOracle {
    private LegacyConjunctionOracle() {
    }

    static Stream<JsonObject> andXor(List<Stream<JsonObject>> combinationResult, int matches) {
        return combinationResult.stream().flatMap(LegacyConjunctionOracle::distinctByKey)
                .collect(Collectors.groupingBy(LegacyConjunctionOracle::key)).values().stream()
                .filter(matching -> matching.size() == matches).map(List::getFirst);
    }

    static Stream<JsonObject> or(List<Stream<JsonObject>> combinationResult) {
        return distinctByKey(combinationResult.stream().flatMap(rows -> rows));
    }

    static Stream<JsonObject> norNand(Stream<JsonObject> combined, Stream<JsonObject> wholeCollection) {
        return Stream.concat(distinctByKey(wholeCollection), combined)
                .collect(Collectors.groupingBy(LegacyConjunctionOracle::key)).values().stream()
                .filter(matching -> matching.size() == 1).map(List::getFirst);
    }

    private static Stream<JsonObject> distinctByKey(Stream<JsonObject> rows) {
        final var seen = new HashSet<>();
        return rows.filter(row -> seen.add(key(row)));
    }

    private static Object key(JsonObject row) {
        final var id = row.get(Globals.PK_FIELD);
        return id != null ? id : row;
    }
}
