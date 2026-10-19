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
    private static final String CLIENT = "org.techhouse.client";
    private static final String ENTRY_POINT = "src/main/java/org/techhouse/Main.java";
    private static final Pattern CLIENT_FORBIDDEN_REFERENCE = Pattern
            .compile("\\borg\\.techhouse\\.(?!(client|ejson|ioc|utils|config\\.Globals|config\\.ConfigKey)\\b)[\\w.]+");
    private static final Pattern CLIENT_REFERENCE = Pattern.compile("\\borg\\.techhouse\\.client\\b[\\w.]*");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile("\\borg\\.techhouse\\.[\\w.]+");
    private static final Set<String> ENTRY_POINT_REFERENCES = Set.of("org.techhouse.client.ClientMain",
            "org.techhouse.ServerMain");

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

    static Set<String> clientReferencesBeyondItsPackages(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(CLIENT)) {
                final var matcher = CLIENT_FORBIDDEN_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> clientReachedFromOutside(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(CLIENT) && !file.relativePath().equals(ENTRY_POINT)) {
                final var matcher = CLIENT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> entryPointReferencesBeyondTheDispatch(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.relativePath().equals(ENTRY_POINT)) {
                final var matcher = PROJECT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    if (!ENTRY_POINT_REFERENCES.contains(matcher.group())) {
                        found.add(file.relativePath() + " -> " + matcher.group());
                    }
                }
            }
        }
        return found;
    }

    @Test
    public void test_client_reaches_only_its_allowed_packages() {
        final var found = clientReferencesBeyondItsPackages(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "the client may use only ejson, ioc, utils, config.Globals and config.ConfigKey,"
                + " since Configuration and Logger create ./db and ./logs and write to stdout: " + found);
    }

    @Test
    public void test_nothing_but_main_reaches_the_client() {
        final var found = clientReachedFromOutside(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "only Main may dispatch to the client: " + found);
    }

    @Test
    public void test_main_only_dispatches() {
        final var found = entryPointReferencesBeyondTheDispatch(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "Main must only choose between ClientMain and ServerMain: " + found);
    }

    @Test
    public void test_a_synthetic_client_class_reaching_configuration_is_caught() {
        final var client = JavaSources.parse("Tool.java", """
                package org.techhouse.client;
                import org.techhouse.config.Configuration;
                import org.techhouse.config.Globals;
                import org.techhouse.config.ConfigKey;
                import org.techhouse.ejson.EJson;
                import org.techhouse.log.Logger;
                class Tool {}
                """);
        assertEquals(Set.of("Tool.java -> org.techhouse.config.Configuration", "Tool.java -> org.techhouse.log.Logger"),
                clientReferencesBeyondItsPackages(List.of(client)));
    }

    @Test
    public void test_a_synthetic_server_class_reaching_the_client_is_caught_but_main_is_not() {
        final var server = JavaSources.parse("src/main/java/org/techhouse/ops/Op.java",
                "package org.techhouse.ops;\nimport org.techhouse.client.Dump;\nclass Op {}\n");
        final var main = JavaSources.parse(ENTRY_POINT,
                "package org.techhouse;\nimport org.techhouse.client.ClientMain;\nclass Main {}\n");
        assertEquals(Set.of("src/main/java/org/techhouse/ops/Op.java -> org.techhouse.client.Dump"),
                clientReachedFromOutside(List.of(server, main)));
    }

    @Test
    public void test_a_synthetic_main_reaching_the_server_layers_is_caught() {
        final var main = JavaSources.parse(ENTRY_POINT, """
                package org.techhouse;
                import org.techhouse.client.ClientMain;
                import org.techhouse.config.Configuration;
                class Main {}
                """);
        assertEquals(Set.of(ENTRY_POINT + " -> org.techhouse.config.Configuration"),
                entryPointReferencesBeyondTheDispatch(List.of(main)));
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
