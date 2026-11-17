import { compareNumbers, checkTime, formatTime, pad, parseTime, requireRange } from "./temporal-text.js";

const DATE_TIME_PATTERN = /^([+-]?\d{4,9})-(\d{2})-(\d{2})T(.+)$/;
const MAX_YEAR = 999_999_999;
const FOUR_DIGIT_YEAR = 9999;
const NANOS_PER_MILLI = 1_000_000;
const MILLIS_PER_SECOND = 1000;
const ZONE_PARTS = ["year", "month", "day", "hour", "minute", "second"] as const;

const FEBRUARY = 2;
const THIRTY_DAY_MONTHS = new Set([4, 6, 9, 11]);

function daysInMonth(year: number, month: number): number {
    if (month === FEBRUARY) {
        return isLeapYear(year) ? 29 : 28;
    }
    return THIRTY_DAY_MONTHS.has(month) ? 30 : 31;
}

function isLeapYear(year: number): boolean {
    return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function formatYear(year: number): string {
    if (Math.abs(year) <= FOUR_DIGIT_YEAR) {
        return year < 0 ? `-${pad(-year, 4)}` : pad(year, 4);
    }
    return year > 0 ? `+${year}` : String(year);
}

function zoneFormatter(zone: string): Intl.DateTimeFormat {
    return new Intl.DateTimeFormat("en-US", {
        timeZone: zone,
        hourCycle: "h23",
        era: "short",
        year: "numeric",
        month: "numeric",
        day: "numeric",
        hour: "numeric",
        minute: "numeric",
        second: "numeric",
    });
}

function wallClockAt(epochMillis: number, zone: string): number[] {
    const parts = zoneFormatter(zone).formatToParts(new Date(epochMillis));
    const value = (type: string): number => Number(parts.find((part) => part.type === type)?.value ?? 0);
    const era = parts.find((part) => part.type === "era")?.value ?? "AD";
    const year = era === "BC" ? 1 - value("year") : value("year");
    return [year, ...ZONE_PARTS.slice(1).map(value)];
}

function utcMillisOf(fields: readonly number[]): number {
    const [year = 0, month = 1, day = 1, hour = 0, minute = 0, second = 0] = fields;
    const date = new Date(Date.UTC(2000, month - 1, day, hour, minute, second));
    date.setUTCFullYear(year);
    return date.getTime();
}

function offsetAt(epochMillis: number, zone: string): number {
    const whole = Math.floor(epochMillis / MILLIS_PER_SECOND) * MILLIS_PER_SECOND;
    return utcMillisOf(wallClockAt(whole, zone)) - whole;
}

export function checkZone(zone: string): string {
    zoneFormatter(zone);
    return zone;
}

export class LocalDateTime {
    readonly year: number;
    readonly month: number;
    readonly day: number;
    readonly hour: number;
    readonly minute: number;
    readonly second: number;
    readonly nanosecond: number;

    private constructor(fields: readonly number[]) {
        const [year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0, nanosecond = 0] = fields;
        requireRange("year", year, -MAX_YEAR, MAX_YEAR);
        requireRange("month", month, 1, 12);
        requireRange("day", day, 1, daysInMonth(year, month));
        checkTime({ hour, minute, second, nanosecond });
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.nanosecond = nanosecond;
        Object.freeze(this);
    }

    static of(
        year: number,
        month: number,
        day: number,
        hour = 0,
        minute = 0,
        second = 0,
        nanosecond = 0,
    ): LocalDateTime {
        return new LocalDateTime([year, month, day, hour, minute, second, nanosecond]);
    }

    static parse(text: string): LocalDateTime {
        const match = DATE_TIME_PATTERN.exec(text);
        if (match === null) {
            throw new RangeError(`not a date-time: ${text}`);
        }
        const [, year = "", month = "", day = "", time = ""] = match;
        const fields = parseTime(time, "date-time");
        return new LocalDateTime([
            Number(year),
            Number(month),
            Number(day),
            fields.hour,
            fields.minute,
            fields.second,
            fields.nanosecond,
        ]);
    }

    static fromDate(date: Date, zone: string): LocalDateTime {
        const epochMillis = date.getTime();
        if (Number.isNaN(epochMillis)) {
            throw new RangeError("the date is invalid");
        }
        const millis = ((epochMillis % MILLIS_PER_SECOND) + MILLIS_PER_SECOND) % MILLIS_PER_SECOND;
        return new LocalDateTime([...wallClockAt(epochMillis, checkZone(zone)), millis * NANOS_PER_MILLI]);
    }

    toDate(zone: string): Date {
        checkZone(zone);
        const wallClock = utcMillisOf(this.#fields().slice(0, 6));
        const firstGuess = wallClock - offsetAt(wallClock, zone);
        const corrected = wallClock - offsetAt(firstGuess, zone);
        return new Date(corrected + Math.floor(this.nanosecond / NANOS_PER_MILLI));
    }

    compareTo(other: LocalDateTime): number {
        return compareNumbers(this.#fields(), other.#fields());
    }

    equals(other: unknown): boolean {
        return other instanceof LocalDateTime && this.compareTo(other) === 0;
    }

    toString(): string {
        return `${formatYear(this.year)}-${pad(this.month, 2)}-${pad(this.day, 2)}T${formatTime(this)}`;
    }

    toJSON(): string {
        return this.toString();
    }

    #fields(): number[] {
        return [this.year, this.month, this.day, this.hour, this.minute, this.second, this.nanosecond];
    }
}
