const MAX_LATITUDE = 90;
const MAX_LONGITUDE = 180;

export class GeoPoint {
    readonly lat: number;
    readonly lng: number;

    private constructor(lat: number, lng: number) {
        if (!Number.isFinite(lat) || Math.abs(lat) > MAX_LATITUDE) {
            throw new RangeError(`latitude must be between -90 and 90, was ${lat}`);
        }
        if (!Number.isFinite(lng) || Math.abs(lng) > MAX_LONGITUDE) {
            throw new RangeError(`longitude must be between -180 and 180, was ${lng}`);
        }
        this.lat = lat;
        this.lng = lng;
        Object.freeze(this);
    }

    static of(lat: number, lng: number): GeoPoint {
        return new GeoPoint(lat, lng);
    }

    equals(other: unknown): boolean {
        return other instanceof GeoPoint && Object.is(other.lat, this.lat) && Object.is(other.lng, this.lng);
    }

    toString(): string {
        return `GeoPoint(${this.lat}, ${this.lng})`;
    }
}
