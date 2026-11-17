import { describe, expect, test } from "vitest";
import { isListenEnd, Route, route } from "../../../src/internal/frame-router.js";

describe("route", () => {
    test("an initial LISTEN reply is a response", () => {
        expect(route({ type: "LISTEN", status: "OK", listenId: "x", isUpdate: false }, false)).toBe(Route.RESPONSE);
    });

    test("an update and a listen end are pushes", () => {
        expect(route({ type: "LISTEN", isUpdate: true }, false)).toBe(Route.PUSH);
        expect(route({ type: "LISTEN", errorCode: "410-1" }, false)).toBe(Route.PUSH);
        expect(route({ type: "LISTEN", isUpdate: "true" }, false)).toBe(Route.RESPONSE);
    });

    test("a LISTEN refusal without a listen id is a response", () => {
        expect(route({ type: "LISTEN", status: "ERROR", errorCode: "400-25" }, false)).toBe(Route.RESPONSE);
    });

    test("a close frame is a server close unless the client asked for it", () => {
        expect(route({ type: "CLOSE_CONNECTION", errorCode: "408-4" }, false)).toBe(Route.SERVER_CLOSE);
        expect(route({ type: "CLOSE_CONNECTION", status: "OK" }, true)).toBe(Route.RESPONSE);
        expect(route({ type: "SAVE" }, false)).toBe(Route.RESPONSE);
    });

    test("isListenEnd looks only at 410-1", () => {
        expect(isListenEnd({ errorCode: "410-1" })).toBe(true);
        expect(isListenEnd({ errorCode: "410-2" })).toBe(false);
    });
});
