package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.ServerInfoRequest;
import org.techhouse.ops.req.validations.RequestValidator;

public class ServerInfoRequestTest {
    @Test
    public void test_parses_from_the_type_alone() {
        final var request = RequestParser.parseRequest("{\"type\":\"SERVER_INFO\"}");

        final var info = assertInstanceOf(ServerInfoRequest.class, request);
        assertEquals(OperationType.SERVER_INFO, info.getType());
        assertNull(info.getDatabaseName());
        assertNull(info.getCollectionName());
        assertFalse(info.isShuttingDown());
        assertTrue(RequestValidator.validate(info).isValid());
    }

    @Test
    public void test_the_shutting_down_mark_is_never_serialised() {
        final var request = new ServerInfoRequest();
        request.markShuttingDown(true);

        assertTrue(request.isShuttingDown());
        assertFalse(IocContainer.get(EJson.class).toJson(request).contains("shuttingDown"));
    }
}
