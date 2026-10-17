package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;
import org.techhouse.ejson.internal.JsonParser;
import org.techhouse.ioc.IocContainer;
import org.techhouse.support.ParserOutcomes;

public class JsonParserScanTest {
    private static final int UNBOUNDED = Integer.MAX_VALUE;

    @BeforeAll
    public static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    private static JsonBaseElement parse(String input) {
        return JsonParser.parse(input, UNBOUNDED, UNBOUNDED);
    }

    private static JsonBaseElement single(String token) {
        return parse("[" + token + "]").asJsonArray().get(0);
    }

    private static String shape(String input) {
        final var out = new StringBuilder();
        ParserOutcomes.shape(parse(input), out);
        return out.toString();
    }

    @Test
    public void objectWithEveryValueKind() {
        assertEquals("{1:a=Double(1.0),1:b=JsonString(x),1:c=JsonBoolean(true),1:d=JsonBoolean(false),1:e=JsonNull,}",
                shape("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":false,\"e\":null}"));
    }

    @Test
    public void stringsAndNumbersEndExactlyWhereTheyShould() {
        assertEquals("[JsonString(a),JsonString(b),]", shape("[\"a\",\"b\"]"));
        assertEquals("[JsonString(),JsonString(),]", shape("[\"\",\"\"]"));
        assertEquals("[Double(-1.5),Double(0.0),]", shape("[-1.5,0]"));
    }

    @Test
    public void exponentForms() {
        assertEquals(100000.0, single("1e5").asJsonNumber().getValue());
        assertEquals(100000.0, single("1E5").asJsonNumber().getValue());
        assertEquals(100000.0, single("1e+5").asJsonNumber().getValue());
        assertEquals(1.0E-5, single("1e-5").asJsonNumber().getValue());
        assertEquals(1500.0, single("1.5e3").asJsonNumber().getValue());
        assertEquals(1e21, single("1e+21").asJsonNumber().getValue().doubleValue());
        assertEquals(-4.9e-324, single("-4.9E-324").asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MAX_VALUE, single("1.7976931348623157e308").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void incompleteExponentIsNotConsumed() {
        assertThrows(UnexpectedCharacterException.class, () -> parse("[1e]"));
        assertThrows(UnexpectedCharacterException.class, () -> parse("[1e+]"));
    }

    @Test
    public void overflowingNumberAbortsTheParse() {
        assertThrows(MalformedJsonException.class, () -> parse("{\"x\":1e400}"));
        assertThrows(MalformedJsonException.class, () -> parse("{\"x\":-1e400}"));
    }

    @Test
    public void escapesAreDecoded() {
        assertEquals("a\"b", single("\"a\\\"b\"").asJsonString().getValue());
        assertEquals("a\\b", single("\"a\\\\b\"").asJsonString().getValue());
        assertEquals("a\nb", single("\"a\\nb\"").asJsonString().getValue());
        assertEquals("a\tb", single("\"a\\tb\"").asJsonString().getValue());
        assertEquals("a/b", single("\"a\\/b\"").asJsonString().getValue());
        assertEquals("\b\f\r", single("\"\\b\\f\\r\"").asJsonString().getValue());
        assertEquals("café", single("\"caf\\u00e9\"").asJsonString().getValue());
        assertEquals("日", single("\"\\u65e5\"").asJsonString().getValue());
    }

    @Test
    public void malformedEscapesAreKeptVerbatim() {
        assertEquals("\\u00", single("\"\\u00\"").asJsonString().getValue());
        assertEquals("\\uzzzz", single("\"\\uzzzz\"").asJsonString().getValue());
        assertEquals("\\q", single("\"\\q\"").asJsonString().getValue());
    }

    @Test
    public void escapedKeysAreDecoded() {
        assertEquals(1.0, parse("{\"k\\u0041\":1}").asJsonObject().get("kA").asJsonNumber().getValue());
    }

    @Test
    public void customTypeTokensAreRecognised() {
        assertInstanceOf(JsonCustom.class, single("\"#time(10:30:00)\""));
        assertEquals("#geo(40.0,-74.0)", single("\"#geo(40.0,-74.0)\"").asJsonCustom().getValue());
        assertEquals(JsonString.class, single("\"#notacustomtype\"").getClass());
        assertEquals(JsonString.class, single("\"#\"").getClass());
    }

    @Test
    public void aCustomShapedKeyKeepsItsText() {
        assertEquals(true, parse("{\"#geo(1,2)\":true}").asJsonObject().get("#geo(1,2)").asJsonBoolean().getValue());
    }

    @Test
    public void literalsAreTheSharedInstances() {
        assertSame(JsonBoolean.TRUE, single("true"));
        assertSame(JsonBoolean.FALSE, single("false"));
        assertSame(JsonNull.INSTANCE, single("null"));
        assertInstanceOf(JsonNumber.class, single("12345"));
    }

    @Test
    public void whitespaceBetweenTokensIsSkipped() {
        assertEquals("{1:a=Double(1.0),}", shape("{ \"a\" :\t1\n}"));
        assertEquals("[Double(1.0),]", shape("[\b1\r]"));
    }

    @Test
    public void lexicalErrors() {
        assertThrows(MissingEndOfStringException.class, () -> parse("[\"abc"));
        assertThrows(MissingEndOfStringException.class, () -> parse("[\"abc\\"));
        assertThrows(MissingEndOfStringException.class, () -> parse("[\"a\\nbc"));
        assertThrows(UnexpectedCharacterException.class, () -> parse("@"));
    }

    @Test
    public void structuralErrorsKeepTheirMessages() {
        assertEquals("Empty JSON array", assertThrows(MalformedJsonException.class, () -> parse("")).getMessage());
        assertEquals("Json must start with either a left bracket or a left brace",
                assertThrows(MalformedJsonException.class, () -> parse("12")).getMessage());
        assertEquals("Expected a value, got SYNTAX",
                assertThrows(MalformedJsonException.class, () -> parse("[,]")).getMessage());
        assertEquals("Expected string key, got NUMBER",
                assertThrows(MalformedJsonException.class, () -> parse("{1:2}")).getMessage());
        assertEquals("Expected colon after key in object, got: STRING",
                assertThrows(MalformedJsonException.class, () -> parse("{\"a\" \"b\"}")).getMessage());
        assertEquals("Expected comma after pair in object, got: CUSTOM",
                assertThrows(MalformedJsonException.class, () -> parse("{\"a\":1 \"#geo(1,2)\"}")).getMessage());
        assertEquals("Expected comma after object in array",
                assertThrows(MalformedJsonException.class, () -> parse("[1 2]")).getMessage());
        assertEquals("Unexpected content after the root value",
                assertThrows(MalformedJsonException.class, () -> parse("{} {}")).getMessage());
        assertThrows(NoSuchElementException.class, () -> parse("{\"a\":1"));
    }

    @Test
    public void aLexicalErrorAfterAStructuralOneWins() {
        assertThrows(UnexpectedCharacterException.class, () -> parse("{\"a\" 1, @}"));
        assertThrows(MissingEndOfStringException.class, () -> parse("[1 2 \"open"));
    }

    @Test
    public void largePayloadParsesLinearly() {
        final var builder = new StringBuilder("{");
        for (var i = 0; i < 20000; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append("field").append(i).append("\":").append(i);
        }
        builder.append('}');

        assertEquals(20000, parse(builder.toString()).asJsonObject().size());
        assertEquals(200_001, parse("[" + "1,".repeat(200_000) + "1]").asJsonArray().size());
    }
}
