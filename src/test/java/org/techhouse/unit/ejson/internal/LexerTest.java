package org.techhouse.unit.ejson.internal;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.*;
import org.techhouse.ejson.internal.Lexer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LexerTest {
    // Lexing valid JSON string with quotes returns JsonString element
    @Test
    public void test_lex_valid_json_string() {
        String input = "\"test string\"";
    
        List<JsonBaseElement> result = Lexer.lex(input);
    
        assertNotNull(result);
        assertEquals(1, result.size());
        assertInstanceOf(JsonString.class, result.getFirst());
        assertEquals("test string", ((JsonString)result.getFirst()).getValue());
    }

    // Lexing empty string returns empty token list
    @Test
    public void test_lex_empty_string() {
        String input = "";
    
        List<JsonBaseElement> result = Lexer.lex(input);
    
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Successfully lexes a valid number and returns JsonNumber token
    @Test
    public void test_lex_number() {
        String input = "12345";
        List<JsonBaseElement> tokens = Lexer.lex(input);
        assertEquals(1, tokens.size());
        assertInstanceOf(JsonNumber.class, tokens.getFirst());
        assertEquals(12345, ((JsonNumber) tokens.getFirst()).getValue());
    }

    // Successfully lexes true/false boolean values and returns JsonBoolean token
    @Test
    public void test_lex_boolean() {
        String inputTrue = "true";
        List<JsonBaseElement> tokensTrue = Lexer.lex(inputTrue);
        assertEquals(1, tokensTrue.size());
        assertInstanceOf(JsonBoolean.class, tokensTrue.getFirst());
        assertTrue(((JsonBoolean) tokensTrue.getFirst()).getValue());

        String inputFalse = "false";
        List<JsonBaseElement> tokensFalse = Lexer.lex(inputFalse);
        assertEquals(1, tokensFalse.size());
        assertInstanceOf(JsonBoolean.class, tokensFalse.getFirst());
        assertFalse(((JsonBoolean) tokensFalse.getFirst()).getValue());
    }

    // Successfully lexes null value and returns JsonNull token
    @Test
    public void test_lex_null() {
        String input = "null";
        List<JsonBaseElement> tokens = Lexer.lex(input);
        assertEquals(1, tokens.size());
        assertInstanceOf(JsonNull.class, tokens.getFirst());
    }


}