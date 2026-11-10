package io.github.christiancg.lwnrdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.christiancg.lwnrdb.client.internal.CustomValues;
import io.github.christiancg.lwnrdb.client.internal.Json;
import io.github.christiancg.lwnrdb.client.internal.PipelineEncoder;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.query.Filters;
import io.github.christiancg.lwnrdb.client.testing.ProtocolFixtures;
import io.github.christiancg.lwnrdb.client.values.CustomType;
import io.github.christiancg.lwnrdb.client.values.GeoDistanceComparator;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

public class CatalogueTest {
    @Test
    public void test_operations_equal_the_catalogue() {
        final var expected = new ArrayList<String>();
        ProtocolFixtures.read("operations.json").get("operations").values()
                .forEach(entry -> expected.add(entry.get("name").asString() + " " + entry.get("scope").asString() + " "
                        + entry.get("adminOnly").asBoolean() + " " + entry.get("allowedInTransaction").asBoolean()));
        final var actual = new ArrayList<String>();
        for (final var operation : Operation.values()) {
            actual.add(operation.name() + " " + operation.scope() + " " + operation.adminOnly() + " "
                    + operation.allowedInTransaction());
        }
        assertEquals(expected, actual);
    }

    @Test
    public void test_error_codes_equal_the_catalogue() {
        final var expected = new ArrayList<String>();
        ProtocolFixtures.read("error-codes.json").get("errorCodes").values()
                .forEach(entry -> expected.add(entry.get("code").asString() + " " + entry.get("name").asString() + " "
                        + entry.get("status").asString() + " " + entry.get("retryable").asBoolean() + " "
                        + entry.get("message").asString(null)));
        final var actual = new ArrayList<String>();
        for (final var code : ErrorCode.values()) {
            actual.add(code.code() + " " + code.name() + " " + code.status() + " " + code.isRetryable() + " "
                    + code.defaultMessage().orElse(null));
        }
        assertEquals(expected, actual);
    }

    @Test
    public void test_custom_types_and_their_operators_equal_the_catalogue() {
        final var catalogue = ProtocolFixtures.read("custom-types.json");
        assertEquals(catalogue.get("detectionRegex").asString(), CustomValues.DETECTION.pattern());
        final var comparators = new ArrayList<String>();
        catalogue.get("geoDistanceComparators").values().forEach(value -> comparators.add(value.asString()));
        assertEquals(comparators, java.util.Arrays.stream(GeoDistanceComparator.values()).map(Enum::name).toList());
        final var types = new TreeSet<String>();
        final var operators = new TreeSet<String>();
        catalogue.get("types").values().forEach(type -> {
            types.add(type.get("name").asString());
            type.get("predicateOperators").values().forEach(name -> operators.add(name.asString()));
            type.get("rankingOperators").values().forEach(name -> operators.add(name.asString()));
        });
        assertEquals(types,
                new TreeSet<>(java.util.Arrays.stream(CustomType.values()).map(CustomType::wireName).toList()));
        final var encoder = new PipelineEncoder(Json.defaults());
        final var produced = new TreeSet<String>();
        for (final var filter : List.of(Filters.distance("f", GeoPoint.of(0, 0), GeoDistanceComparator.EQUALS, 1),
                Filters.within("f", GeoPoint.of(0, 0), GeoPoint.of(1, 0), GeoPoint.of(0, 1)),
                Filters.nearest("f", Vector.of(1), 1))) {
            produced.add(encoder.filter(filter).get("customOperatorName").asString());
        }
        assertEquals(operators, produced);
    }
}
