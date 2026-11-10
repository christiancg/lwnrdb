import type { Document } from "../document.js";
import type { QueryTarget } from "../internal/collection-ops.js";
import type { AnalyzeResult } from "../results.js";
import { MapOperation, Order, requireText, type Filter, type PipelineStep } from "./types.js";

const COUNT_FIELD = "count";
const ORDERS = new Set<unknown>(Object.values(Order));

function requireInteger(name: string, value: number, min: number): number {
    if (!Number.isSafeInteger(value) || value < min) {
        throw new RangeError(`${name} must be an integer of at least ${min}: ${value}`);
    }
    return value;
}

export class Query<R> {
    readonly #target: QueryTarget<R>;
    readonly #steps: readonly PipelineStep[];
    readonly #dirtyRead: boolean;

    constructor(target: QueryTarget<R>, steps: readonly PipelineStep[] = [], dirtyRead = false) {
        this.#target = target;
        this.#steps = Object.freeze([...steps]);
        this.#dirtyRead = dirtyRead;
    }

    get steps(): readonly PipelineStep[] {
        return this.#steps;
    }

    get isDirtyRead(): boolean {
        return this.#dirtyRead;
    }

    filter(filter: Filter): Query<R> {
        if (typeof filter !== "object") {
            throw new TypeError("filter needs a Filter");
        }
        return this.#then({ type: "FILTER", filter });
    }

    map(...operations: MapOperation[]): Query<R> {
        if (operations.length === 0) {
            throw new RangeError("map needs at least one operation");
        }
        if (!operations.every((operation) => operation instanceof MapOperation)) {
            throw new TypeError("map takes MapOperations from MapOps.set and MapOps.remove");
        }
        return this.#then({ type: "MAP", operations: Object.freeze([...operations]) });
    }

    groupBy(field: string): Query<R> {
        return this.#then({ type: "GROUP_BY", field: requireText(field, "field") });
    }

    join(collection: string, localField: string, remoteField: string, asField: string): Query<R> {
        return this.#then({
            type: "JOIN",
            collection: requireText(collection, "collection"),
            localField: requireText(localField, "localField"),
            remoteField: requireText(remoteField, "remoteField"),
            asField: requireText(asField, "asField"),
        });
    }

    distinct(field?: string): Query<R> {
        return this.#then({ type: "DISTINCT", field: field === undefined ? undefined : requireText(field, "field") });
    }

    skip(skip: number): Query<R> {
        return this.#then({ type: "SKIP", skip: requireInteger("skip", skip, 0) });
    }

    limit(limit: number): Query<R> {
        return this.#then({ type: "LIMIT", limit: requireInteger("limit", limit, 1) });
    }

    sort(field: string, order: Order = Order.ASC): Query<R> {
        if (!ORDERS.has(order)) {
            throw new TypeError(`order must be Order.ASC or Order.DESC: ${order}`);
        }
        return this.#then({ type: "SORT", field: requireText(field, "field"), ascending: order === Order.ASC });
    }

    reduce(script: string, ...seed: [] | [initialValue: unknown, resultField?: string]): Query<R> {
        const [initialValue, resultField] = seed;
        const hasInitialValue = seed.length > 0;
        return this.#then({
            type: "REDUCE",
            script: requireText(script, "script"),
            hasInitialValue,
            initialValue: hasInitialValue ? (initialValue ?? null) : undefined,
            resultField: resultField === undefined ? undefined : requireText(resultField, "resultField"),
        });
    }

    dirtyRead(): Query<R> {
        return new Query(this.#target, this.#steps, true);
    }

    async toArray(): Promise<R[]> {
        const rows = await this.toDocuments();
        return rows.map((row) => this.#target.codec.decode(row));
    }

    toDocuments(): Promise<Document[]> {
        return this.#target.aggregate(this.#steps, this.#dirtyRead);
    }

    async first(): Promise<R | undefined> {
        const [row] = await this.limit(1).toDocuments();
        return row === undefined ? undefined : this.#target.codec.decode(row);
    }

    async count(): Promise<number> {
        const [row] = await this.#then({ type: "COUNT" }).toDocuments();
        const count = row?.[COUNT_FIELD];
        return typeof count === "number" ? count : 0;
    }

    analyze(): Promise<AnalyzeResult> {
        return this.#target.analyze(this.#steps, this.#dirtyRead);
    }

    #then(step: PipelineStep): Query<R> {
        return new Query(this.#target, [...this.#steps, Object.freeze(step)], this.#dirtyRead);
    }
}
