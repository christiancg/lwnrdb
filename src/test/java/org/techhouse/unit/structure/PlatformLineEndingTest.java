package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;

public class PlatformLineEndingTest {
    private static final Pattern PLATFORM_LINE_ENDING = Pattern
            .compile("\\bSystem\\s*\\.\\s*lineSeparator\\s*\\(|\\.\\s*newLine\\s*\\(");

    @Test
    public void test_every_line_the_server_writes_ends_with_a_line_feed() {
        assertEquals("\n", Globals.NEWLINE);
    }

    @Test
    public void test_no_main_source_reads_the_platform_line_separator() {
        assertEquals(Set.of(),
                SingleDefinitionTest.filesWhoseCodeMatches(JavaSources.under(JavaSources.MAIN), PLATFORM_LINE_ENDING),
                "Data files and wire frames end lines with Globals.NEWLINE on every platform, or a Windows server"
                        + " writes CRLF");
    }

    @Test
    public void test_a_synthetic_platform_separator_is_caught_but_not_inside_a_string_or_comment() {
        final var separator = JavaSources.parse("Separator.java",
                "class Separator { String s = System.lineSeparator(); }");
        final var newLine = JavaSources.parse("NewLine.java",
                "class NewLine { void f(java.io.BufferedWriter w)" + " throws Exception { w.newLine(); } }");
        final var mentioned = JavaSources.parse("Mentioned.java",
                "class Mentioned { String s = \"System.lineSeparator()\"; }\n// writer.newLine()\n");
        assertEquals(Set.of("Separator.java", "NewLine.java"), SingleDefinitionTest
                .filesWhoseCodeMatches(List.of(separator, newLine, mentioned), PLATFORM_LINE_ENDING));
    }
}
