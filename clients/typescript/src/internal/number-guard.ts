import { MappingError } from "../errors/errors.js";

export function label(path: string): string {
    return path === "" ? "the value" : `field '${path}'`;
}

export function checkNumber(value: number, path: string): number {
    if (!Number.isFinite(value)) {
        throw new MappingError(`${label(path)} is not a finite number: ${value}`);
    }
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
        throw new MappingError(
            `${label(path)} exceeds 2^53, which the server stores as a double; store it as a string`,
        );
    }
    return value;
}
