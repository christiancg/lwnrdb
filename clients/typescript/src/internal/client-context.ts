import type { Listens } from "./listens.js";
import type { RequestChannel } from "./request-channel.js";
import type { RequestEncoder } from "./request-encoder.js";
import type { ScriptRequestEncoder } from "./script-request-encoder.js";

export interface ClientContext {
    readonly channel: RequestChannel;
    readonly encoder: RequestEncoder;
    readonly scripts: ScriptRequestEncoder;
    readonly listens: Listens | undefined;
    readonly username: string | undefined;
    readonly requestTimeoutMs: number;
}

export function withChannel(context: ClientContext, channel: RequestChannel): ClientContext {
    return { ...context, channel };
}
