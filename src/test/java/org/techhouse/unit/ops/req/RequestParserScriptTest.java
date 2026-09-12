package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;

public class RequestParserScriptTest {
    @Test
    public void test_parse_run_script_request() {
        String msg = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"db\","
                + "\"script\":\"const s = \\\"hi\\\";\\nreturn s;\",\"args\":{\"a\":1}}";
        final var result = (org.techhouse.ops.req.RunScriptRequest) RequestParser.parseRequest(msg);
        assertEquals(OperationType.RUN_SCRIPT, result.getType());
        assertEquals("db", result.getDatabaseName());
        assertEquals("const s = \"hi\";\nreturn s;", result.getScript());
        assertEquals(1, result.getArgs().get("a").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_parse_run_script_request_without_args() {
        String msg = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"db\",\"script\":\"return 1;\"}";
        final var result = (org.techhouse.ops.req.RunScriptRequest) RequestParser.parseRequest(msg);
        assertEquals("return 1;", result.getScript());
        assertTrue(result.getArgs().entrySet().isEmpty());
    }
}
