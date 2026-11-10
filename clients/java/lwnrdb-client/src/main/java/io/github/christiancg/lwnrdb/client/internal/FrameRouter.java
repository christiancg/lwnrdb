package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.protocol.Operation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class FrameRouter {
    public static final String LISTEN_ENDED_CODE = "410-1";

    public enum Route {
        RESPONSE, PUSH, SERVER_CLOSE
    }

    private FrameRouter() {
    }

    public static Route route(ObjectNode frame, boolean awaitingClose) {
        final var type = text(frame.get("type"));
        if (Operation.LISTEN.name().equals(type) && isPush(frame)) {
            return Route.PUSH;
        }
        if (Operation.CLOSE_CONNECTION.name().equals(type) && !awaitingClose) {
            return Route.SERVER_CLOSE;
        }
        return Route.RESPONSE;
    }

    public static boolean isListenEnd(ObjectNode frame) {
        return LISTEN_ENDED_CODE.equals(text(frame.get("errorCode")));
    }

    private static boolean isPush(ObjectNode frame) {
        final var update = frame.get("isUpdate");
        return update != null && update.isBoolean() && update.booleanValue() || isListenEnd(frame);
    }

    private static String text(JsonNode node) {
        return node == null || !node.isString() ? null : node.stringValue();
    }
}
