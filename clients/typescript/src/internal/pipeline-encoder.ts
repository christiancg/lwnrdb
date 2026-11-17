import type { Filter, MapOperation, MidOperation, PipelineStep } from "../query/types.js";
import { Literal } from "../query/types.js";
import { formatCustom } from "./custom-values.js";
import { encodeValue, type EncodeOptions, type Json, type JsonObject } from "./values-codec.js";

const CUSTOM_OPERATOR = "customOperatorName";

export class PipelineEncoder {
    readonly #options: EncodeOptions;

    constructor(options: EncodeOptions = {}) {
        this.#options = options;
    }

    steps(steps: readonly PipelineStep[]): Json[] {
        return steps.map((step) => this.#step(step));
    }

    filter(filter: Filter): JsonObject {
        switch (filter.kind) {
            case "field":
                return {
                    fieldOperatorType: filter.operator,
                    field: filter.field,
                    value: this.value(filter.value, filter.field),
                };
            case "conjunction":
                return {
                    conjunctionType: filter.conjunction,
                    operators: filter.operands.map((operand) => this.filter(operand)),
                };
            case "distance":
                return {
                    [CUSTOM_OPERATOR]: "distance",
                    field: filter.field,
                    value: formatCustom(filter.target),
                    comparator: filter.comparator,
                    distance: filter.metres,
                };
            case "within":
                return {
                    [CUSTOM_OPERATOR]: "within",
                    field: filter.field,
                    polygon: filter.polygon.map(formatCustom),
                };
            case "nearest":
                return {
                    [CUSTOM_OPERATOR]: "nearest",
                    field: filter.field,
                    value: formatCustom(filter.query),
                    k: filter.k,
                    ...(filter.isExact ? { exact: true } : {}),
                };
            case "script":
                return { script: filter.source };
        }
    }

    value(value: unknown, path = ""): Json {
        return encodeValue(value, path, this.#options);
    }

    #step(step: PipelineStep): JsonObject {
        switch (step.type) {
            case "FILTER":
                return { type: step.type, operator: this.filter(step.filter) };
            case "MAP":
                return {
                    type: step.type,
                    operators: step.operations.map((operation) => this.#mapOperation(operation)),
                };
            case "GROUP_BY":
                return { type: step.type, fieldName: step.field };
            case "JOIN":
                return {
                    type: step.type,
                    joinCollection: step.collection,
                    localField: step.localField,
                    remoteField: step.remoteField,
                    asField: step.asField,
                };
            case "COUNT":
                return { type: step.type };
            case "DISTINCT":
                return step.field === undefined ? { type: step.type } : { type: step.type, fieldName: step.field };
            case "LIMIT":
                return { type: step.type, limit: step.limit };
            case "SKIP":
                return { type: step.type, skip: step.skip };
            case "SORT":
                return { type: step.type, fieldName: step.field, ascending: step.ascending };
            case "REDUCE":
                return {
                    type: step.type,
                    script: step.script,
                    ...(step.hasInitialValue ? { initialValue: this.value(step.initialValue, "initialValue") } : {}),
                    ...(step.resultField === undefined ? {} : { resultField: step.resultField }),
                };
        }
    }

    #mapOperation(operation: MapOperation): JsonObject {
        return {
            fieldName: operation.field,
            ...(operation.operation === undefined ? {} : { operator: this.#midOperation(operation.operation) }),
            ...(operation.condition === undefined ? {} : { condition: this.filter(operation.condition) }),
        };
    }

    #midOperation(operation: MidOperation): JsonObject {
        switch (operation.kind) {
            case "array":
                return {
                    type: operation.operator,
                    operands: operation.operands.map((operand) => this.#operand(operand)),
                };
            case "function":
                return { type: operation.function, operand: operation.field };
            case "cast":
                return {
                    type: "CAST",
                    fieldName: operation.field,
                    toType: operation.type,
                    ...(operation.customType === undefined ? {} : { customTypeName: operation.customType }),
                };
            case "script":
                return { type: "SCRIPT", script: operation.source };
        }
    }

    #operand(operand: unknown): Json {
        if (operand instanceof Literal) {
            return Literal.WIRE_PREFIX + operand.text;
        }
        if (typeof operand === "string") {
            return operand;
        }
        return this.value(operand);
    }
}
