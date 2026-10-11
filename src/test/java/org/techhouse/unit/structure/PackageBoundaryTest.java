package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSources.JavaSourceFile;

public class PackageBoundaryTest {
    private static final String SIMPLEJS = "org.techhouse.simplejs";
    private static final String SIMPLEJS_HOST = "org.techhouse.simplejs.host";
    private static final String EJSON = "org.techhouse.ejson";
    private static final Pattern SERVER_LAYER_REFERENCE = Pattern
            .compile("\\borg\\.techhouse\\.(ops|cache|conn|ioc)\\.[\\w.]+");
    private static final Pattern SIMPLEJS_REFERENCE = Pattern.compile("\\borg\\.techhouse\\.simplejs\\.[\\w.]+");
    private static final String REGEX_ENGINE = "org.techhouse.simplejs.internal.regex.";
    private static final String SYNTAX_ERROR = "org.techhouse.simplejs.exceptions.SyntaxErrorException";

    static Set<String> simplejsServerLayerReferences(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(SIMPLEJS) && !file.isInPackage(SIMPLEJS_HOST)) {
                final var matcher = SERVER_LAYER_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> ejsonSimplejsReferencesBeyondTheRegexEngine(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(EJSON)) {
                final var matcher = SIMPLEJS_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    final var reference = matcher.group();
                    if (!reference.startsWith(REGEX_ENGINE) && !reference.equals(SYNTAX_ERROR)) {
                        found.add(file.relativePath() + " -> " + reference);
                    }
                }
            }
        }
        return found;
    }

    @Test
    public void test_only_the_simplejs_host_reaches_the_server_layers() {
        final var found = simplejsServerLayerReferences(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "simplejs/host is the only package that may import ops/cache/conn/ioc: " + found);
    }

    @Test
    public void test_ejson_takes_only_the_regex_engine_from_simplejs() {
        final var found = ejsonSimplejsReferencesBeyondTheRegexEngine(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(),
                "ejson may use only simplejs.internal.regex and SyntaxErrorException from simplejs: " + found);
    }

    @Test
    public void test_a_synthetic_engine_class_reaching_ops_is_caught_but_the_host_is_not() {
        final var engine = JavaSources.parse("Engine.java",
                "package org.techhouse.simplejs.internal;\nimport org.techhouse.ops.ErrorCode;\nclass Engine {}\n");
        final var host = JavaSources.parse("Host.java",
                "package org.techhouse.simplejs.host;\nimport org.techhouse.ops.ErrorCode;\nclass Host {}\n");
        assertEquals(Set.of("Engine.java -> org.techhouse.ops.ErrorCode"),
                simplejsServerLayerReferences(List.of(engine, host)));
    }

    @Test
    public void test_a_synthetic_ejson_class_reaching_the_interpreter_is_caught() {
        final var codec = JavaSources.parse("Codec.java", """
                package org.techhouse.ejson.validate;
                import org.techhouse.simplejs.internal.regex.RegexParser;
                import org.techhouse.simplejs.exceptions.SyntaxErrorException;
                class Codec { org.techhouse.simplejs.internal.interpreter.Interpreter i; }
                """);
        assertEquals(Set.of("Codec.java -> org.techhouse.simplejs.internal.interpreter.Interpreter"),
                ejsonSimplejsReferencesBeyondTheRegexEngine(List.of(codec)));
    }
}
