import type { Status } from "../protocol/status.js";
import { errorCodeDetails, type ErrorCodeDetails } from "./error-codes.js";

export interface LwnrdbErrorOptions {
    readonly code?: string | undefined;
    readonly status?: Status | undefined;
    readonly operation?: string | undefined;
    readonly cause?: unknown;
}

export class LwnrdbError extends Error {
    readonly rawCode: string | undefined;
    readonly status: Status | undefined;
    readonly operation: string | undefined;

    constructor(message: string, options: LwnrdbErrorOptions = {}) {
        super(message, options.cause === undefined ? undefined : { cause: options.cause });
        this.name = new.target.name;
        this.rawCode = options.code;
        this.status = options.status;
        this.operation = options.operation;
    }

    get code(): ErrorCodeDetails | undefined {
        return errorCodeDetails(this.rawCode);
    }

    hasCode(code: string): boolean {
        return this.rawCode === code;
    }
}

export class AuthenticationError extends LwnrdbError {}

export class ForbiddenError extends LwnrdbError {}

export class NotFoundError extends LwnrdbError {}

export class ConflictError extends LwnrdbError {}

export class ListenEndedError extends LwnrdbError {}

export class UnavailableError extends LwnrdbError {}

export class UnknownOutcomeError extends LwnrdbError {}

export class ConnectionLostError extends LwnrdbError {}

export class RequestTimeoutError extends ConnectionLostError {}

export class ServerClosedConnectionError extends LwnrdbError {}

export class PoolExhaustedError extends LwnrdbError {}

export class MappingError extends LwnrdbError {}
