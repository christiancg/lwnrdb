import { Vector } from "../values/vector.js";
import { requireText } from "./types.js";

export class NearestFilter {
    readonly kind = "nearest";
    readonly field: string;
    readonly query: Vector;
    readonly k: number;
    readonly isExact: boolean;

    constructor(field: string, query: Vector, k: number, exact = false) {
        if (!Number.isInteger(k) || k <= 0) {
            throw new RangeError(`nearest needs an integer k > 0, got ${k}`);
        }
        if (!(query instanceof Vector)) {
            throw new TypeError("nearest needs a Vector query");
        }
        this.field = requireText(field, "field");
        this.query = query;
        this.k = k;
        this.isExact = exact;
        Object.freeze(this);
    }

    exact(): NearestFilter {
        return new NearestFilter(this.field, this.query, this.k, true);
    }
}
