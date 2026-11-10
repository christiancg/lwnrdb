import { describe, expect, test } from "vitest";
import {
    AuthenticationError,
    ConflictError,
    ForbiddenError,
    ListenEndedError,
    LwnrdbError,
    NotFoundError,
    UnavailableError,
    UnknownOutcomeError,
} from "../../../src/index.js";
import {
    booleanOf,
    check,
    documents,
    numberOf,
    raise,
    strings,
    textOf,
} from "../../../src/internal/response-decoder.js";

describe("raise", () => {
    test.each([
        ["401-1", AuthenticationError],
        ["403-2", ForbiddenError],
        ["404-11", NotFoundError],
        ["409-16", ConflictError],
        ["410-1", ListenEndedError],
        ["503-2", UnavailableError],
        ["500-33", UnknownOutcomeError],
        ["500-34", UnknownOutcomeError],
        ["400-1", LwnrdbError],
    ])("%s maps by its range", (code, type) => {
        const error = raise({ type: "SAVE", status: "ERROR", message: "no", errorCode: code });
        expect(error).toBeInstanceOf(type);
        expect(error.constructor).toBe(type);
        expect(error.message).toBe(`no (${code})`);
        expect(error.operation).toBe("SAVE");
        expect(error.hasCode(code)).toBe(true);
        expect(error.name).toBe(type.name);
    });

    test("falls back to the status, then the default message", () => {
        expect(raise({ status: "UNAUTHENTICATED" })).toBeInstanceOf(AuthenticationError);
        expect(raise({ status: "FORBIDDEN" })).toBeInstanceOf(ForbiddenError);
        expect(raise({ status: "NOT_FOUND" })).toBeInstanceOf(NotFoundError);
        expect(raise({ status: "WHATEVER" }).status).toBe("ERROR");
        expect(raise({}).status).toBeUndefined();
        expect(raise({ status: "ERROR", errorCode: "400-2" }).message).toBe(
            "Entry size exceeds maximum allowed size (400-2)",
        );
        expect(raise({ status: "ERROR", errorCode: "999-1" }).message).toBe("request failed (999-1)");
        expect(raise({ status: "ERROR", errorCode: "400-2" }).code?.name).toBe("ENTRY_TOO_LARGE");
    });
});

describe("check and the readers", () => {
    test("passes OK and NO_OPERATION frames through", () => {
        const frame = { status: "OK" };
        expect(check(frame)).toBe(frame);
        expect(check({ status: "NO_OPERATION" })).toEqual({ status: "NO_OPERATION" });
        expect(check({})).toEqual({});
        expect(() => check({ status: "ERROR" })).toThrow(LwnrdbError);
    });

    test("read lists, texts, numbers and booleans defensively", () => {
        expect(documents({ rows: [{ a: 1 }, 2, null, [1]] }, "rows")).toEqual([{ a: 1 }]);
        expect(documents({ rows: "x" }, "rows")).toEqual([]);
        expect(strings(["a", 1, null, { b: 2 }])).toEqual(["a", "1", "null", '{"b":2}']);
        expect(strings("x")).toEqual([]);
        expect(textOf(undefined)).toBeUndefined();
        expect(textOf(null)).toBeUndefined();
        expect(numberOf("1")).toBeUndefined();
        expect(booleanOf("true", false)).toBe(false);
    });
});
