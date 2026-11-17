import { constants } from "node:buffer";
import { checkZone } from "./values/local-date-time.js";
import { checkRetryPolicy, DEFAULT_RETRY_POLICY, type RetryPolicy } from "./retry-policy.js";
import type { TlsSettings } from "./tls-settings.js";

export interface ServerAddress {
    readonly host: string;
    readonly port: number;
}

export interface Credentials {
    readonly username: string;
    readonly password: string;
}

export interface ClientSettings {
    readonly hosts?: readonly ServerAddress[];
    readonly credentials?: Credentials;
    readonly tls?: TlsSettings;
    readonly poolSize?: number;
    readonly connectTimeoutMs?: number;
    readonly requestTimeoutMs?: number;
    readonly acquireTimeoutMs?: number;
    readonly shutdownTimeoutMs?: number;
    readonly maxReplyBytes?: number;
    readonly retryPolicy?: RetryPolicy;
    readonly temporalZone?: string;
}

export interface ResolvedSettings {
    readonly hosts: readonly ServerAddress[];
    readonly credentials: Credentials | undefined;
    readonly tls: TlsSettings | undefined;
    readonly poolSize: number;
    readonly connectTimeoutMs: number;
    readonly requestTimeoutMs: number;
    readonly acquireTimeoutMs: number;
    readonly shutdownTimeoutMs: number;
    readonly maxReplyBytes: number;
    readonly retryPolicy: RetryPolicy;
    readonly temporalZone: string | undefined;
}

export const DEFAULT_PORT = 8989;
export const DEFAULT_POOL_SIZE = 8;
export const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
export const DEFAULT_REQUEST_TIMEOUT_MS = 330_000;
export const DEFAULT_ACQUIRE_TIMEOUT_MS = 30_000;
export const DEFAULT_SHUTDOWN_TIMEOUT_MS = 10_000;
export const DEFAULT_MAX_REPLY_BYTES = 256 * 1024 * 1024;
const MAX_TIMER_MS = 2 ** 31 - 1;
const MAX_PORT = 65_535;

function positiveInteger(name: string, value: number | undefined, fallback: number, max: number): number {
    const resolved = value ?? fallback;
    if (!Number.isInteger(resolved) || resolved < 1 || resolved > max) {
        throw new RangeError(`${name} must be an integer between 1 and ${max}: ${resolved}`);
    }
    return resolved;
}

function checkAddress(address: ServerAddress): ServerAddress {
    if (typeof address.host !== "string" || address.host === "") {
        throw new TypeError("a host needs a name or an address");
    }
    if (!Number.isInteger(address.port) || address.port < 1 || address.port > MAX_PORT) {
        throw new RangeError(`port must be between 1 and 65535: ${address.port}`);
    }
    return Object.freeze({ host: address.host, port: address.port });
}

function checkHosts(hosts: readonly ServerAddress[] | undefined): readonly ServerAddress[] {
    if (hosts === undefined) {
        return Object.freeze([Object.freeze({ host: "localhost", port: DEFAULT_PORT })]);
    }
    if (hosts.length === 0) {
        throw new RangeError("hosts must name at least one server");
    }
    return Object.freeze(hosts.map(checkAddress));
}

function checkCredentials(credentials: Credentials | undefined): Credentials | undefined {
    if (credentials === undefined) {
        return undefined;
    }
    if (typeof credentials.username !== "string" || typeof credentials.password !== "string") {
        throw new TypeError("credentials need a username and a password");
    }
    return Object.freeze({ username: credentials.username, password: credentials.password });
}

export function resolveSettings(settings: ClientSettings = {}): ResolvedSettings {
    return Object.freeze({
        hosts: checkHosts(settings.hosts),
        credentials: checkCredentials(settings.credentials),
        tls: settings.tls === undefined ? undefined : Object.freeze({ ...settings.tls }),
        poolSize: positiveInteger("poolSize", settings.poolSize, DEFAULT_POOL_SIZE, Number.MAX_SAFE_INTEGER),
        connectTimeoutMs: positiveInteger(
            "connectTimeoutMs",
            settings.connectTimeoutMs,
            DEFAULT_CONNECT_TIMEOUT_MS,
            MAX_TIMER_MS,
        ),
        requestTimeoutMs: positiveInteger(
            "requestTimeoutMs",
            settings.requestTimeoutMs,
            DEFAULT_REQUEST_TIMEOUT_MS,
            MAX_TIMER_MS,
        ),
        acquireTimeoutMs: positiveInteger(
            "acquireTimeoutMs",
            settings.acquireTimeoutMs,
            DEFAULT_ACQUIRE_TIMEOUT_MS,
            MAX_TIMER_MS,
        ),
        shutdownTimeoutMs: positiveInteger(
            "shutdownTimeoutMs",
            settings.shutdownTimeoutMs,
            DEFAULT_SHUTDOWN_TIMEOUT_MS,
            MAX_TIMER_MS,
        ),
        maxReplyBytes: positiveInteger(
            "maxReplyBytes",
            settings.maxReplyBytes,
            DEFAULT_MAX_REPLY_BYTES,
            constants.MAX_STRING_LENGTH,
        ),
        retryPolicy: checkRetryPolicy(settings.retryPolicy ?? DEFAULT_RETRY_POLICY),
        temporalZone: settings.temporalZone === undefined ? undefined : checkZone(settings.temporalZone),
    });
}
