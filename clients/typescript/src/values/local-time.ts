import { checkTime, compareNumbers, formatTime, parseTime } from "./temporal-text.js";

export class LocalTime {
    readonly hour: number;
    readonly minute: number;
    readonly second: number;
    readonly nanosecond: number;

    private constructor(hour: number, minute: number, second: number, nanosecond: number) {
        checkTime({ hour, minute, second, nanosecond });
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.nanosecond = nanosecond;
        Object.freeze(this);
    }

    static of(hour: number, minute: number, second = 0, nanosecond = 0): LocalTime {
        return new LocalTime(hour, minute, second, nanosecond);
    }

    static parse(text: string): LocalTime {
        const fields = parseTime(text, "time");
        return new LocalTime(fields.hour, fields.minute, fields.second, fields.nanosecond);
    }

    compareTo(other: LocalTime): number {
        return compareNumbers(this.#fields(), other.#fields());
    }

    equals(other: unknown): boolean {
        return other instanceof LocalTime && this.compareTo(other) === 0;
    }

    toString(): string {
        return formatTime(this);
    }

    toJSON(): string {
        return this.toString();
    }

    #fields(): number[] {
        return [this.hour, this.minute, this.second, this.nanosecond];
    }
}
