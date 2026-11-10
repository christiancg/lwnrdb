package io.github.christiancg.lwnrdb.buildtools;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.TraditionalJavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class ApiDocInjector {
    private static final int EXPECTED_ARGUMENTS = 3;

    private final Path sources;
    private final ApiDocs docs;
    private final Path output;

    public ApiDocInjector(Path sources, Path docsFolder, Path output) {
        this.sources = sources;
        this.docs = ApiDocs.load(docsFolder);
        this.output = output;
    }

    public static void main(String[] args) {
        if (args.length != EXPECTED_ARGUMENTS) {
            throw new ApiDocException("usage: ApiDocInjector <sources> <apidocs> <output>");
        }
        new ApiDocInjector(Path.of(args[0]), Path.of(args[1]), Path.of(args[2])).run();
    }

    public void run() {
        final var problems = new ArrayList<String>();
        final var documentedTypes = new HashSet<String>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (final var file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                final var unit = PublicApi.parse(Files.readString(file, StandardCharsets.UTF_8));
                inject(unit, documentedTypes, problems);
                final var target = output.resolve(sources.relativize(file).toString());
                Files.createDirectories(Objects.requireNonNull(target.getParent(), "a source file has a folder"));
                Files.writeString(target, unit.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reportUnknownTypes(documentedTypes, problems);
        if (!problems.isEmpty()) {
            throw new ApiDocException(String.join("\n", problems));
        }
    }

    private void inject(CompilationUnit unit, Set<String> documentedTypes, List<String> problems) {
        for (final var type : PublicApi.typesOf(unit)) {
            final var doc = docs.type(type.qualifiedName());
            if (doc == null) {
                continue;
            }
            documentedTypes.add(type.qualifiedName());
            setJavadoc(type.declaration(), doc.typeText());
            for (final var section : doc.members().entrySet()) {
                final NodeWithJavadoc<?> member = type.members().get(section.getKey());
                if (member == null) {
                    problems.add(doc.file() + ": '## " + section.getKey() + "' matches no member of "
                            + type.qualifiedName());
                } else {
                    setJavadoc(member, section.getValue());
                }
            }
        }
    }

    private void reportUnknownTypes(Set<String> documentedTypes, List<String> problems) {
        for (final var entry : docs.types().entrySet()) {
            if (!documentedTypes.contains(entry.getKey())) {
                problems.add(entry.getValue().file() + ": names no public type in " + sources);
            }
        }
    }

    private static void setJavadoc(NodeWithJavadoc<?> node, String markdown) {
        if (!markdown.isBlank()) {
            node.setComment(new TraditionalJavadocComment(JavadocText.toJavadoc(markdown)));
        }
    }
}
