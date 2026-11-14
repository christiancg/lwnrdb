package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestUtils;

public class RequestParserFailOnUpdateTest {
    private static final String PREFIX = "{\"type\":\"SAVE\",\"databaseName\":\"shop\",\"collectionName\":\"orders\"";

    @BeforeAll
    public static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    private static SaveRequest parse(String rest) {
        return assertInstanceOf(SaveRequest.class, RequestParser.parseRequest(PREFIX + rest + "}"));
    }

    @Test
    public void test_a_save_without_the_flag_does_not_fail_on_update() {
        assertFalse(parse(",\"object\":{\"_id\":\"o-1\"}").isFailOnUpdate());
    }

    @Test
    public void test_the_flag_is_bound_beside_an_object_id() {
        final var request = parse(",\"failOnUpdate\":true,\"object\":{\"_id\":\"o-1\"}");

        assertTrue(request.isFailOnUpdate());
        assertEquals("o-1", request.get_id());
    }

    @Test
    public void test_the_flag_is_bound_beside_a_request_id() {
        final var request = parse(",\"failOnUpdate\":true,\"_id\":\"o-1\",\"object\":{\"a\":1}");

        assertTrue(request.isFailOnUpdate());
        assertEquals("o-1", request.get_id());
    }

    @Test
    public void test_false_is_bound_as_false() {
        assertFalse(parse(",\"failOnUpdate\":false,\"object\":{\"a\":1}").isFailOnUpdate());
    }

    @Test
    public void test_a_flag_that_is_not_a_boolean_is_an_invalid_command() {
        assertThrows(InvalidCommandException.class,
                () -> RequestParser.parseRequest(PREFIX + ",\"failOnUpdate\":\"yes\",\"object\":{\"a\":1}}"));
    }
}
