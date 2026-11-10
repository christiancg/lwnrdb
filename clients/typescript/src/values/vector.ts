export class Vector {
    readonly #components: Float64Array;

    private constructor(components: Float64Array) {
        if (components.length === 0) {
            throw new RangeError("a vector needs at least one component");
        }
        components.forEach((component, index) => {
            if (!Number.isFinite(component)) {
                throw new RangeError(`vector component ${index} is not finite: ${component}`);
            }
        });
        this.#components = components;
        Object.freeze(this);
    }

    static of(...components: number[]): Vector {
        return new Vector(Float64Array.from(components));
    }

    static from(components: ArrayLike<number>): Vector {
        return new Vector(Float64Array.from(components));
    }

    get dimensions(): number {
        return this.#components.length;
    }

    component(index: number): number {
        const value = this.#components[index];
        if (value === undefined) {
            throw new RangeError(`component ${index} is outside a vector of ${this.dimensions} dimensions`);
        }
        return value;
    }

    toArray(): number[] {
        return Array.from(this.#components);
    }

    equals(other: unknown): boolean {
        return (
            other instanceof Vector &&
            other.dimensions === this.dimensions &&
            this.#components.every((component, index) => Object.is(component, other.component(index)))
        );
    }

    toString(): string {
        return `Vector[${this.toArray().join(", ")}]`;
    }
}
