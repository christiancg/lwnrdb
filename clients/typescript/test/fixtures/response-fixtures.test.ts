import { describe, expect, test } from "vitest";
import { RETRYABLE_CODES } from "../../src/errors/error-codes.js";
import {
    GeoPoint,
    ListenEndedError,
    LocalDateTime,
    LocalTime,
    LwnrdbError,
    UnavailableError,
    UnknownOutcomeError,
    Vector,
} from "../../src/index.js";
import { isListenEnd, Route, route } from "../../src/internal/frame-router.js";
import { check, document, raise } from "../../src/internal/response-decoder.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { responses } from "../support/protocol-fixtures.js";

const ERROR_PREFIX = "error:";

function expectError(frame: JsonObject, code: string): void {
    expect(route(frame, false)).toBe(Route.RESPONSE);
    let caught: unknown;
    try {
        check(frame);
    } catch (error) {
        caught = error;
    }
    expect(caught).toBeInstanceOf(LwnrdbError);
    const error = caught as LwnrdbError;
    expect(error.rawCode).toBe(code);
    expect(error.message).toContain(code);
    if (RETRYABLE_CODES.has(code)) {
        expect(error).toBeInstanceOf(UnavailableError);
    }
    if (code === "500-33" || code === "500-34") {
        expect(error).toBeInstanceOf(UnknownOutcomeError);
    }
}

describe("response fixtures", () => {
    test.each(responses().map((fixture) => [fixture.name, fixture.body] as const))("%s", (_, body) => {
        const frame = body.frame as JsonObject;
        const expectation = body.expect as string;
        switch (expectation) {
            case "ok":
                expect(route(frame, false)).toBe(Route.RESPONSE);
                expect(check(frame)).toBe(frame);
                break;
            case "push":
                expect(route(frame, false)).toBe(Route.PUSH);
                expect(isListenEnd(frame)).toBe(false);
                break;
            case "listenEnded":
                expect(route(frame, false)).toBe(Route.PUSH);
                expect(raise(frame)).toBeInstanceOf(ListenEndedError);
                break;
            case "serverClosed":
                expect(route(frame, false)).toBe(Route.SERVER_CLOSE);
                break;
            default:
                expect(expectation.startsWith(ERROR_PREFIX)).toBe(true);
                expectError(frame, expectation.slice(ERROR_PREFIX.length));
        }
    });

    test("custom values in a reply decode to their classes", () => {
        const fixture = responses().find((candidate) => candidate.name === "ok.find_by_id");
        const found = document(fixture?.body.frame as JsonObject, "object");
        expect(found?.openedAt).toEqual(LocalDateTime.of(2024, 1, 1, 10, 0));
        expect(found?.opensAt).toEqual(LocalTime.of(9, 30));
        expect(found?.location).toEqual(GeoPoint.of(40.71, -74.0));
        const embedding = found?.embedding as Vector;
        expect(embedding.equals(Vector.of(0.12, 4.4e-4, -0, 1.0e21))).toBe(true);
        expect(Object.is(embedding.component(2), -0)).toBe(true);
    });

    test("the listen push fixture decodes the -0.0 component with its sign", () => {
        const fixture = responses().find((candidate) => candidate.name === "frames.listen_push");
        const frame = fixture?.body.frame as JsonObject;
        const [row] = frame.results as JsonObject[];
        const decoded = document({ row: row ?? {} }, "row");
        expect(Object.is((decoded?.embedding as Vector).component(2), -0)).toBe(true);
    });
});
