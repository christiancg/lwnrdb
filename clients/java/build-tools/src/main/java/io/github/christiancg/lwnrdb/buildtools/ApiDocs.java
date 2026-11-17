package io.github.christiancg.lwnrdb.buildtools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class ApiDocs {
    private static final String MEMBER_HEADING = "## ";
    private static final String DOC_EXTENSION = ".md";

    private final Map<String, TypeDoc> types;

    private ApiDocs(Map<String, TypeDoc> types) {
        this.types = types;
    }

    public record TypeDoc(Path file, String typeText, Map<String, String> members) {
    }

    public static ApiDocs load(Path folder) {
        final var types = new TreeMap<String, TypeDoc>();
        if (!Files.isDirectory(folder)) {
            return new ApiDocs(types);
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (final var file : files.filter(path -> path.toString().endsWith(DOC_EXTENSION)).sorted().toList()) {
                final var name = String.valueOf(file.getFileName());
                types.put(name.substring(0, name.length() - DOC_EXTENSION.length()),
                        parse(file, Files.readAllLines(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ApiDocs(types);
    }

    static TypeDoc parse(Path file, List<String> lines) {
        final var sections = new LinkedHashMap<String, StringBuilder>();
        final var typeText = new StringBuilder();
        var current = typeText;
        for (final var line : lines) {
            if (line.startsWith(MEMBER_HEADING)) {
                final var signature = Signatures.normalise(line.substring(MEMBER_HEADING.length()));
                if (sections.containsKey(signature)) {
                    throw new ApiDocException(file + ": duplicate section '" + signature + "'");
                }
                current = new StringBuilder();
                sections.put(signature, current);
            } else {
                current.append(line).append('\n');
            }
        }
        final var members = new LinkedHashMap<String, String>();
        sections.forEach((signature, text) -> members.put(signature, text.toString().strip()));
        return new TypeDoc(file, typeText.toString().strip(), members);
    }

    public Map<String, TypeDoc> types() {
        return types;
    }

    public TypeDoc type(String qualifiedName) {
        return types.get(qualifiedName);
    }
}
