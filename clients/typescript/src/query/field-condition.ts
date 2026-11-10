import { FieldOperator, requireText, type Filter } from "./types.js";

export class FieldCondition {
    readonly field: string;

    constructor(field: string) {
        this.field = requireText(field, "field");
        Object.freeze(this);
    }

    eq(value: unknown): Filter {
        return this.#of(FieldOperator.EQUALS, value);
    }

    ne(value: unknown): Filter {
        return this.#of(FieldOperator.NOT_EQUALS, value);
    }

    gt(value: unknown): Filter {
        return this.#of(FieldOperator.GREATER_THAN, value);
    }

    gte(value: unknown): Filter {
        return this.#of(FieldOperator.GREATER_THAN_EQUALS, value);
    }

    lt(value: unknown): Filter {
        return this.#of(FieldOperator.SMALLER_THAN, value);
    }

    lte(value: unknown): Filter {
        return this.#of(FieldOperator.SMALLER_THAN_EQUALS, value);
    }

    in(...values: unknown[]): Filter {
        return this.#of(FieldOperator.IN, Object.freeze([...values]));
    }

    notIn(...values: unknown[]): Filter {
        return this.#of(FieldOperator.NOT_IN, Object.freeze([...values]));
    }

    contains(value: unknown): Filter {
        return this.#of(FieldOperator.CONTAINS, value);
    }

    #of(operator: FieldOperator, value: unknown): Filter {
        if (value === undefined) {
            throw new TypeError(`${operator} on '${this.field}' needs a value; use null to match a missing field`);
        }
        return Object.freeze({ kind: "field", operator, field: this.field, value });
    }
}
