package io.github.christiancg.lwnrdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.testing.JsonTrees;
import io.github.christiancg.lwnrdb.client.testing.ProtocolFixtures;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

public class WireFixturesTest {
    @TestFactory
    public Stream<DynamicTest> test_every_request_fixture_is_produced_by_its_builder_call() {
        final var cases = WireFixtureCases.all();
        return ProtocolFixtures.requests().stream().map(fixture -> DynamicTest.dynamicTest(fixture.name(), () -> {
            final var call = cases.get(fixture.name());
            assertTrue(call != null, fixture.name() + " has no builder mapping");
            final var recorder = new Recorder();
            call.accept(recorder);
            final var expected = fixture.body().get("request");
            final var actual = recorder.channel.last();
            assertTrue(JsonTrees.equivalent(expected, actual),
                    fixture.name() + "\nexpected " + expected + "\nactual   " + actual);
        }));
    }

    @Test
    public void test_every_mapping_names_an_existing_fixture() {
        final var names = new HashSet<String>();
        ProtocolFixtures.requests().forEach(fixture -> names.add(fixture.name()));
        final var stale = new TreeSet<>(WireFixtureCases.all().keySet());
        stale.removeAll(names);
        assertTrue(stale.isEmpty(), "mappings without a fixture: " + stale);
    }

    @Test
    public void test_every_operation_is_produced_by_some_mapping() {
        final var produced = new TreeSet<String>();
        WireFixtureCases.all().values().forEach(call -> {
            final var recorder = new Recorder();
            call.accept(recorder);
            recorder.channel.requests().forEach(request -> produced.add(request.get("type").asString()));
        });
        final var expected = new TreeSet<String>();
        for (final var operation : Operation.values()) {
            expected.add(operation.name());
        }
        assertEquals(expected, produced);
    }
}
