package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.ServerInfoResponse;

public class ServerInfoResponseTest {
    private static final EJson eJson = IocContainer.get(EJson.class);

    private static JsonObject ready() {
        final var server = new JsonObject();
        server.addProperty("state", "READY");
        return server;
    }

    @Test
    public void test_serialises_the_server_object() {
        final var response = new ServerInfoResponse("Ok", ready());

        final var frame = eJson.fromJson(eJson.toJson(response), JsonObject.class);

        assertEquals(OperationType.SERVER_INFO, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("SERVER_INFO", frame.get("type").asJsonString().getValue());
        assertEquals("READY", frame.get("server").asJsonObject().get("state").asJsonString().getValue());
    }

    @Test
    public void test_the_estimate_covers_the_payload() {
        final var response = new ServerInfoResponse("Ok", ready());

        assertTrue(response.estimatedJsonChars() >= eJson.toJson(response.getServer()).length());
    }
}
