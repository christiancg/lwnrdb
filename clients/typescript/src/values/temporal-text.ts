const NANOS_PER_MILLI = 1_000_000;
const NANOS_PER_MICRO = 1_000;
const MAX_NANO = 999_999_999;
const TIME_PATTERN = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/;

export interface TimeFields {
    readonly hour: number;
    readonly minute: number;
    readonly second: number;
    readonly nanosecond: number;
}

export function pad(value: number, width: number): string {
    return String(value).padStart(width, "0");
}

export function requireRange(label: string, value: number, min: number, max: number): number {
    if (!Number.isInteger(value) || value < min || value > max) {
        throw new RangeError(`${label} must be an integer between ${min} and ${max}, was ${value}`);
    }
    return value;
}

export function checkTime(fields: TimeFields): void {
    requireRange("hour", fields.hour, 0, 23);
    requireRange("minute", fields.minute, 0, 59);
    requireRange("second", fields.second, 0, 59);
    requireRange("nanosecond", fields.nanosecond, 0, MAX_NANO);
}

export function formatTime(fields: TimeFields): string {
    const hoursAndMinutes = `${pad(fields.hour, 2)}:${pad(fields.minute, 2)}`;
    if (fields.second === 0 && fields.nanosecond === 0) {
        return hoursAndMinutes;
    }
    return `${hoursAndMinutes}:${pad(fields.second, 2)}${formatFraction(fields.nanosecond)}`;
}

function formatFraction(nanosecond: number): string {
    if (nanosecond === 0) {
        return "";
    }
    if (nanosecond % NANOS_PER_MILLI === 0) {
        return `.${pad(nanosecond / NANOS_PER_MILLI, 3)}`;
    }
    if (nanosecond % NANOS_PER_MICRO === 0) {
        return `.${pad(nanosecond / NANOS_PER_MICRO, 6)}`;
    }
    return `.${pad(nanosecond, 9)}`;
}

export function parseTime(text: string, label: string): TimeFields {
    const match = TIME_PATTERN.exec(text);
    if (match === null) {
        throw new RangeError(`not a ${label}: ${text}`);
    }
    const [, hour = "", minute = "", second = "0", fraction = ""] = match;
    const fields = {
        hour: Number(hour),
        minute: Number(minute),
        second: Number(second),
        nanosecond: Number(fraction.padEnd(9, "0")),
    };
    checkTime(fields);
    return fields;
}

export function compareNumbers(left: readonly number[], right: readonly number[]): number {
    for (let index = 0; index < left.length; index++) {
        const difference = (left[index] ?? 0) - (right[index] ?? 0);
        if (difference !== 0) {
            return Math.sign(difference);
        }
    }
    return 0;
}
