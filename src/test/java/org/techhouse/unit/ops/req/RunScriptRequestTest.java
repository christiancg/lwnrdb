package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RunScriptRequest;

public class RunScriptRequestTest {
    @Test
    public void test_no_arg_constructor_sets_type_and_empty_args() {
        final var request = new RunScriptRequest();
        assertEquals(OperationType.RUN_SCRIPT, request.getType());
        assertNull(request.getDatabaseName());
        assertNull(request.getCollectionName());
        assertNull(request.getScript());
        assertTrue(request.getArgs().entrySet().isEmpty());
    }

    @Test
    public void test_full_constructor_sets_fields() {
        final var args = new JsonObject();
        args.add("n", new JsonNumber(1));
        final var request = new RunScriptRequest("myDb", "return 1;", args);
        assertEquals("myDb", request.getDatabaseName());
        assertEquals("return 1;", request.getScript());
        assertEquals(1, request.getArgs().get("n").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_setters_update_fields() {
        final var request = new RunScriptRequest();
        request.setScript("return 2;");
        final var args = new JsonObject();
        args.add("k", new JsonNumber(2));
        request.setArgs(args);
        assertEquals("return 2;", request.getScript());
        assertEquals(2, request.getArgs().get("k").asJsonNumber().getValue().intValue());
    }
}
