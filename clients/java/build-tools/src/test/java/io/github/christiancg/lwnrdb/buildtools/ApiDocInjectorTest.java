package io.github.christiancg.lwnrdb.buildtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ApiDocInjectorTest {
    private static final String SOURCE = """
            package demo.api;

            public class Widget {
                public static final int LIMIT = 3;

                public Widget(String name) {
                }

                public void resize(int width) {
                }

                public void resize(int width, java.util.List<String> labels) {
                }

                public void describe(String... parts) {
                }

                @Override
                public String toString() {
                    return "w";
                }

                public enum Color {
                    RED, BLUE
                }
            }
            """;

    @TempDir
    Path folder;
    private Path sources;
    private Path docs;
    private Path output;

    @BeforeEach
    public void writeSources() throws IOException {
        sources = folder.resolve("src");
        Files.createDirectories(sources.resolve("demo/api"));
        Files.writeString(sources.resolve("demo/api/Widget.java"), SOURCE, StandardCharsets.UTF_8);
        Files.createDirectories(sources.resolve("demo/internal"));
        Files.writeString(sources.resolve("demo/internal/Hidden.java"),
                "package demo.internal;\n\npublic class " + "Hidden {\n    public void secret() {\n    }\n}\n",
                StandardCharsets.UTF_8);
        docs = Files.createDirectories(folder.resolve("docs"));
        output = folder.resolve("out");
    }

    private void doc(String type, String text) throws IOException {
        Files.writeString(docs.resolve(type + ".md"), text, StandardCharsets.UTF_8);
    }

    private String injected() throws IOException {
        new ApiDocInjector(sources, docs, output).run();
        return Files.readString(output.resolve("demo/api/Widget.java"), StandardCharsets.UTF_8);
    }

    @Test
    public void test_sections_land_on_the_matching_declarations() throws IOException {
        doc("demo.api.Widget", """
                A **widget**.

                ```java
                new Widget("w");
                ```

                ## LIMIT

                The limit.

                ## Widget(String)

                Builds one.

                @param name the `name`

                ## resize(int)

                Resizes.

                ## resize(int, List)

                Resizes and labels.

                ## describe(String...)

                Describes */ safely.

                ## toString()

                The text.
                """);
        doc("demo.api.Widget.Color", "A colour.\n\n## RED\n\nRed.\n");
        final var text = injected();
        assertTrue(text.contains("<strong>widget</strong>"), text);
        assertTrue(text.contains("<pre><code class=\"language-java\">"), text);
        assertTrue(text.contains("@param name the {@code name}"), text);
        assertTrue(text.contains("Resizes and labels."), text);
        assertTrue(text.contains("*&#47; safely"), text);
        assertTrue(text.contains("Red."), text);
        assertTrue(text.indexOf("Resizes.") < text.indexOf("public void resize(int width)"), text);
        assertTrue(Files.readString(output.resolve("demo/internal/Hidden.java")).contains("secret"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d",
                folder.resolve("classes").toString(), output.resolve("demo/api/Widget.java").toString()));
    }

    @Test
    public void test_a_section_matching_nothing_fails_and_names_the_file() throws IOException {
        doc("demo.api.Widget", "A widget.\n\n## shrink(int)\n\nNope.\n");
        final var error = assertThrows(ApiDocException.class, this::injected);
        assertTrue(error.getMessage().contains("shrink(int)"), error.getMessage());
        assertTrue(error.getMessage().contains("demo.api.Widget.md"), error.getMessage());
    }

    @Test
    public void test_a_file_for_an_unknown_type_fails() throws IOException {
        doc("demo.api.Gadget", "A gadget.\n");
        assertThrows(ApiDocException.class, this::injected);
    }

    @Test
    public void test_a_duplicate_section_fails() {
        assertThrows(ApiDocException.class,
                () -> ApiDocs.parse(Path.of("x.md"), List.of("t", "## a()", "x", "## a( )", "y")));
    }

    @Test
    public void test_coverage_lists_undocumented_members_but_not_overrides_or_internals() throws IOException {
        doc("demo.api.Widget", "A widget.\n\n## LIMIT\n\nThe limit.\n");
        final var missing = ApiDocCoverage.missing(sources, docs);
        assertTrue(missing.contains("demo.api.Widget#resize(int, List)"), missing.toString());
        assertTrue(missing.contains("demo.api.Widget.Color"), missing.toString());
        assertTrue(missing.contains("demo.api.Widget.Color#BLUE"), missing.toString());
        assertFalse(missing.contains("demo.api.Widget#toString()"), missing.toString());
        assertFalse(missing.contains("demo.api.Widget#LIMIT"), missing.toString());
        assertFalse(missing.stream().anyMatch(item -> item.contains("Hidden")), missing.toString());
    }

    @Test
    public void test_main_validates_its_arguments_and_a_missing_docs_folder_is_empty() {
        assertThrows(ApiDocException.class, () -> ApiDocInjector.main(new String[0]));
        assertTrue(ApiDocs.load(folder.resolve("absent")).types().isEmpty());
    }

    @Test
    public void test_markdown_without_block_tags_is_plain_html() {
        assertEquals("<p>Just <em>text</em>.</p>", JavadocText.toJavadoc("Just *text*."));
        assertEquals("@return the {@code x}", JavadocText.toJavadoc("@return the `x`"));
    }
}
