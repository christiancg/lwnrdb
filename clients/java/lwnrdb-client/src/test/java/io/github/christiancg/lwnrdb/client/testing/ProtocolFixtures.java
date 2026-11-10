package io.github.christiancg.lwnrdb.client.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class ProtocolFixtures {
    public static final Path ROOT = Path.of(System.getProperty("lwnrdb.protocol", "../../protocol"));

    private ProtocolFixtures() {
    }

    public record Case(String name, ObjectNode body) {
    }

    public static List<Case> requests() {
        return casesIn(ROOT.resolve("fixtures/requests"));
    }

    public static List<Case> responses() {
        return casesIn(ROOT.resolve("fixtures/responses"));
    }

    public static JsonNode read(String file) {
        try {
            return FakeServer.MAPPER.readTree(Files.readString(ROOT.resolve(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Case> casesIn(Path folder) {
        final var cases = new ArrayList<Case>();
        try (Stream<Path> files = Files.list(folder)) {
            for (final var file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                final var document = FakeServer.MAPPER.readTree(Files.readString(file));
                for (final var entry : document.get("cases").values()) {
                    cases.add(new Case(entry.get("name").asString(), (ObjectNode) entry));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }
}
