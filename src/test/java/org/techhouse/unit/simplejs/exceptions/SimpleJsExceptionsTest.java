package org.techhouse.unit.simplejs.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;

public class SimpleJsExceptionsTest {
    // Unexpected character message includes the character and position
    @Test
    public void test_unexpected_character_message() {
        assertEquals("Unexpected character @ at position: 3", new UnexpectedCharacterException('@', 3).getMessage());
    }

    // Unterminated string message includes the position
    @Test
    public void test_unterminated_string_message() {
        assertEquals("Unterminated string starting at position: 5", new UnterminatedStringException(5).getMessage());
    }

    // Unterminated comment message includes the position
    @Test
    public void test_unterminated_comment_message() {
        assertEquals("Unterminated comment starting at position: 2", new UnterminatedCommentException(2).getMessage());
    }

    // Unterminated regex message includes the position
    @Test
    public void test_unterminated_regex_message() {
        assertEquals("Unterminated regular expression starting at position: 7",
                new UnterminatedRegexException(7).getMessage());
    }

    // Unterminated template message includes the position
    @Test
    public void test_unterminated_template_message() {
        assertEquals("Unterminated template literal starting at position: 0",
                new UnterminatedTemplateException(0).getMessage());
    }
}
