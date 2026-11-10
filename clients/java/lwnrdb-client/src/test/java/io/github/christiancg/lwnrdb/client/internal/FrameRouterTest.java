package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.christiancg.lwnrdb.client.testing.FakeServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class FrameRouterTest {
    private static ObjectNode listen() {
        return FakeServer.MAPPER.createObjectNode().put("type", "LISTEN").put("status", "OK");
    }

    @Test
    public void test_listen_frames_route_by_update_flag_and_end_code() {
        assertEquals(FrameRouter.Route.PUSH, FrameRouter.route(listen().put("isUpdate", true), false));
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(listen().put("isUpdate", false), false));
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(listen().put("isUpdate", "true"), false));
        assertEquals(FrameRouter.Route.PUSH, FrameRouter.route(listen().put("errorCode", "410-1"), false));
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(listen().put("errorCode", "400-25"), false));
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(listen(), false));
    }

    @Test
    public void test_a_close_frame_is_a_response_only_when_one_was_asked_for() {
        final var close = FakeServer.MAPPER.createObjectNode().put("type", "CLOSE_CONNECTION");
        assertEquals(FrameRouter.Route.SERVER_CLOSE, FrameRouter.route(close, false));
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(close, true));
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(FakeServer.MAPPER.createObjectNode(), false));
    }
}
