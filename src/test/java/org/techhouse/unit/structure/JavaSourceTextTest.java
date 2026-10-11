package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSourceText.SourceComment;

public class JavaSourceTextTest {

    private static List<SourceComment> commentsIn(String source) {
        return JavaSourceText.of(source).comments();
    }

    @Test
    public void test_a_line_comment_and_a_block_comment_are_found_with_their_lines() {
        final var comments = commentsIn("class A {\n    int x; // trailing\n    /* block */ int y;\n}\n");
        assertEquals(List.of(new SourceComment(2, "// trailing"), new SourceComment(3, "/* block */")), comments);
    }

    @Test
    public void test_a_javadoc_spanning_lines_is_one_comment() {
        final var comments = commentsIn("/**\n * Docs.\n */\nclass A {}\n");
        assertEquals(1, comments.size());
        assertEquals(1, comments.getFirst().line());
        assertTrue(comments.getFirst().text().startsWith("/**"));
    }

    @Test
    public void test_slashes_inside_a_string_are_not_a_comment() {
        assertTrue(commentsIn("class A { String u = \"http://x\"; String s = \"a // b /* c */\"; }").isEmpty());
    }

    @Test
    public void test_an_escaped_quote_does_not_end_the_string() {
        final var text = JavaSourceText.of("class A { String s = \"say \\\"// hi\\\"\"; }");
        assertTrue(text.comments().isEmpty());
        assertEquals(List.of("say \"// hi\""), text.stringLiterals());
    }

    @Test
    public void test_char_literals_holding_a_quote_or_a_slash_are_not_strings_or_comments() {
        final var text = JavaSourceText.of("class A { char q = '\"'; char e = '\\''; char s = '/'; int d = 1 / 2; }");
        assertTrue(text.comments().isEmpty());
        assertTrue(text.stringLiterals().isEmpty());
    }

    @Test
    public void test_a_text_block_holding_javascript_comments_has_no_comments() {
        final var text = JavaSourceText.of("class A { String js = \"\"\"\n    // js\n    /* js */ x;\n    \"\"\"; }\n");
        assertTrue(text.comments().isEmpty());
        assertEquals(1, text.stringLiterals().size());
    }

    @Test
    public void test_an_escaped_triple_quote_does_not_end_the_text_block() {
        final var text = JavaSourceText.of("class A { String s = \"\"\"\n a \\\"\"\" // still text\n \"\"\"; }\n");
        assertTrue(text.comments().isEmpty());
    }

    @Test
    public void test_every_directive_form_is_recognised() {
        final var comments = commentsIn("""
                int a = 1; // NOPMD - reason
                // CHECKSTYLE:OFF
                // @formatter:off
                //noinspection unchecked
                // an ordinary comment
                """);
        assertEquals(List.of(true, true, true, true, false),
                comments.stream().map(SourceComment::isToolchainDirective).toList());
    }

    @Test
    public void test_code_blanks_literals_and_comments_but_keeps_line_numbers() {
        final var source = "class A {\n  String s = \"x\"; // c\n  /* b\n */ int y;\n}\n";
        final var code = JavaSourceText.of(source).code();
        assertEquals(source.length(), code.length());
        assertEquals(source.chars().filter(c -> c == '\n').count(), code.chars().filter(c -> c == '\n').count());
        assertFalse(code.contains("\"x\""));
        assertFalse(code.contains("//"));
        assertTrue(code.contains("int y;"));
    }

    @Test
    public void test_string_literals_are_reported() {
        assertEquals(List.of("rw", "a\\b"),
                JavaSourceText.of("class A { String m = \"rw\", p = \"a\\\\b\"; }").stringLiterals());
    }

    @Test
    public void test_crlf_line_endings_count_one_line_each() {
        assertEquals(List.of(new SourceComment(3, "// three")), commentsIn("class A {\r\n\r\n// three\r\n}\r\n"));
    }

    @Test
    public void test_an_unterminated_comment_or_string_consumes_to_the_end() {
        assertEquals(1, commentsIn("class A { /* never closed\n int x;\n").size());
        assertTrue(commentsIn("class A { String s = \"never closed\n }\n").isEmpty());
    }

    @Test
    public void test_line_of_maps_offsets_to_one_based_lines() {
        final var text = JavaSourceText.of("a\nbc\nd");
        assertEquals(1, text.lineOf(0));
        assertEquals(2, text.lineOf(2));
        assertEquals(2, text.lineOf(3));
        assertEquals(3, text.lineOf(5));
    }
}
