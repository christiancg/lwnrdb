import { describe, expect, test } from "vitest";
import {
    CustomType,
    distance,
    ERROR_CODES,
    GeoDistanceComparator,
    GeoPoint,
    nearest,
    OPERATIONS,
    Vector,
    within,
} from "../../src/index.js";
import { DETECTION } from "../../src/internal/custom-values.js";
import { PipelineEncoder } from "../../src/internal/pipeline-encoder.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { read } from "../support/protocol-fixtures.js";

describe("the protocol catalogue", () => {
    test("operations equal operations.json", () => {
        const expected = (read("operations.json").operations as JsonObject[]).map((entry) => ({
            name: entry.name,
            scope: entry.scope,
            adminOnly: entry.adminOnly,
            allowedInTransaction: entry.allowedInTransaction,
        }));
        const actual = OPERATIONS.map(({ name, scope, adminOnly, allowedInTransaction }) => ({
            name,
            scope,
            adminOnly,
            allowedInTransaction,
        }));
        expect(actual).toEqual(expected);
    });

    test("error codes equal error-codes.json", () => {
        const expected = (read("error-codes.json").errorCodes as JsonObject[]).map((entry) => ({ ...entry }));
        expect(ERROR_CODES.map((details) => ({ ...details }))).toEqual(expected);
    });

    test("custom types, their operators and the geo comparators equal custom-types.json", () => {
        const catalogue = read("custom-types.json");
        expect(DETECTION.source).toBe(catalogue.detectionRegex);
        expect(Object.values(GeoDistanceComparator)).toEqual(catalogue.geoDistanceComparators);
        const types = catalogue.types as JsonObject[];
        expect(Object.values(CustomType).sort()).toEqual(types.map((type) => type.name).sort());
        const operators = types
            .flatMap((type) => [...(type.predicateOperators as string[]), ...(type.rankingOperators as string[])])
            .sort();
        const encoder = new PipelineEncoder();
        const produced = [
            distance("f", GeoPoint.of(0, 0), GeoDistanceComparator.EQUALS, 1),
            within("f", GeoPoint.of(0, 0), GeoPoint.of(1, 0), GeoPoint.of(0, 1)),
            nearest("f", Vector.of(1), 1),
        ]
            .map((filter) => encoder.filter(filter).customOperatorName)
            .sort();
        expect(produced).toEqual(operators);
    });
});
