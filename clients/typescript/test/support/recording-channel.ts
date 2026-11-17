import type { ClientContext } from "../../src/internal/client-context.js";
import type { RequestChannel } from "../../src/internal/request-channel.js";
import { RequestEncoder } from "../../src/internal/request-encoder.js";
import { check } from "../../src/internal/response-decoder.js";
import { ScriptRequestEncoder } from "../../src/internal/script-request-encoder.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import type { Operation } from "../../src/index.js";

export type Replier = (request: JsonObject) => JsonObject;

export function ok(request: JsonObject, extra: JsonObject = {}): JsonObject {
    return { type: request.type ?? null, status: "OK", message: "Ok", ...extra };
}

export function error(request: JsonObject, status: string, code: string, message = "refused"): JsonObject {
    return { type: request.type ?? null, status, message, errorCode: code };
}

export class RecordingChannel implements RequestChannel {
    readonly requests: JsonObject[] = [];
    readonly operations: Operation[] = [];
    replies: Replier = (request) => ok(request);

    send(operation: Operation, request: JsonObject): Promise<JsonObject> {
        this.requests.push(structuredClone(request));
        this.operations.push(operation);
        try {
            return Promise.resolve(check(this.replies(request)));
        } catch (failure) {
            return Promise.reject(failure instanceof Error ? failure : new Error(String(failure)));
        }
    }

    last(): JsonObject {
        const last = this.requests.at(-1);
        if (last === undefined) {
            throw new Error("no request was sent");
        }
        return last;
    }
}

export function recordingContext(channel: RequestChannel, temporalZone?: string): ClientContext {
    const encoder = new RequestEncoder({ temporalZone });
    return {
        channel,
        encoder,
        scripts: new ScriptRequestEncoder(encoder),
        listens: undefined,
        username: "alice",
        requestTimeoutMs: 5000,
    };
}
