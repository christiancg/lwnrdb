import { MappingError } from "../errors/errors.js";
import { CustomType } from "../values/custom-type.js";
import { GeoPoint } from "../values/geo-point.js";
import { LocalDateTime } from "../values/local-date-time.js";
import { LocalTime } from "../values/local-time.js";
import { Vector } from "../values/vector.js";

export const DETECTION = /^#[a-zA-Z0-9]{3,20}\(.*\)$/;
const SEPARATOR = ",";
const NAME_END = "(";

export type CustomValue = LocalDateTime | LocalTime | GeoPoint | Vector;

export function looksCustom(text: string): boolean {
    return text.startsWith("#") && DETECTION.test(text);
}

export function isCustomValue(value: unknown): value is CustomValue {
    return (
        value instanceof LocalDateTime ||
        value instanceof LocalTime ||
        value instanceof GeoPoint ||
        value instanceof Vector
    );
}

export function formatComponent(value: number): string {
    return Object.is(value, -0) ? "-0.0" : String(value);
}

export function formatCustom(value: CustomValue): string {
    if (value instanceof GeoPoint) {
        return wrap(CustomType.GEO, formatComponent(value.lat) + SEPARATOR + formatComponent(value.lng));
    }
    if (value instanceof Vector) {
        return wrap(CustomType.VECTOR, value.toArray().map(formatComponent).join(SEPARATOR));
    }
    if (value instanceof LocalDateTime) {
        return wrap(CustomType.DATETIME, value.toString());
    }
    return wrap(CustomType.TIME, value.toString());
}

export function parseCustom(text: string): CustomValue | string {
    const nameEnd = text.indexOf(NAME_END);
    const name = text.slice(1, nameEnd);
    const body = text.slice(nameEnd + 1, -1);
    try {
        switch (name) {
            case CustomType.DATETIME:
                return LocalDateTime.parse(body);
            case CustomType.TIME:
                return LocalTime.parse(body);
            case CustomType.GEO:
                return parseGeo(body);
            case CustomType.VECTOR:
                return Vector.from(parseComponents(body));
            default:
                return text;
        }
    } catch (error) {
        throw new MappingError(`the server sent a malformed ${name} value: ${text}`, { cause: error });
    }
}

function parseGeo(body: string): GeoPoint {
    const [lat, lng, ...extra] = parseComponents(body);
    if (lat === undefined || lng === undefined || extra.length > 0) {
        throw new RangeError("a geo value holds two coordinates");
    }
    return GeoPoint.of(lat, lng);
}

function parseComponents(body: string): number[] {
    return body.split(SEPARATOR).map((part) => {
        const trimmed = part.trim();
        const value = Number(trimmed);
        if (trimmed === "" || Number.isNaN(value)) {
            throw new RangeError(`not a number: ${part}`);
        }
        return value;
    });
}

function wrap(type: CustomType, body: string): string {
    return `#${type}(${body})`;
}
