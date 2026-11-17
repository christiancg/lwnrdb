import type { JsonObject } from "./values-codec.js";

export const LISTEN_ENDED_CODE = "410-1";

export const Route = {
    RESPONSE: "RESPONSE",
    PUSH: "PUSH",
    SERVER_CLOSE: "SERVER_CLOSE",
} as const;

export type Route = (typeof Route)[keyof typeof Route];

export function route(frame: JsonObject, awaitingClose: boolean): Route {
    const type = frame.type;
    if (type === "LISTEN" && isPush(frame)) {
        return Route.PUSH;
    }
    if (type === "CLOSE_CONNECTION" && !awaitingClose) {
        return Route.SERVER_CLOSE;
    }
    return Route.RESPONSE;
}

export function isListenEnd(frame: JsonObject): boolean {
    return frame.errorCode === LISTEN_ENDED_CODE;
}

function isPush(frame: JsonObject): boolean {
    return frame.isUpdate === true || isListenEnd(frame);
}
