package io.github.christiancg.lwnrdb.buildtools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class ApiDocCoverage {
    private ApiDocCoverage() {
    }

    public static void main(String[] args) {
        missing(Path.of(args[0]), Path.of(args[1])).forEach(System.out::println);
    }

    public static List<String> missing(Path sources, Path docsFolder) {
        final var docs = ApiDocs.load(docsFolder);
        final var missing = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (final var file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                final var unit = PublicApi.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (PublicApi.isInternal(unit)) {
                    continue;
                }
                for (final var type : PublicApi.typesOf(unit)) {
                    final var doc = docs.type(type.qualifiedName());
                    if (doc == null || doc.typeText().isBlank()) {
                        missing.add(type.qualifiedName());
                    }
                    for (final var member : type.members().keySet()) {
                        if (type.overrides().contains(member)) {
                            continue;
                        }
                        if (doc == null || doc.members().getOrDefault(member, "").isBlank()) {
                            missing.add(type.qualifiedName() + "#" + member);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return missing;
    }
}
