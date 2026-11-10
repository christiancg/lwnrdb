import { describe, expect, test } from "vitest";
import { collectionName, databaseName, documentId } from "../../../src/internal/names.js";

describe("names", () => {
    test("accepts names of 3-64 characters from the allowed set", () => {
        expect(databaseName("shop")).toBe("shop");
        expect(collectionName("a_b-C9")).toBe("a_b-C9");
        expect(databaseName("x".repeat(64))).toHaveLength(64);
    });

    test("rejects names that are short, long, or hold other characters", () => {
        expect(() => databaseName("ab")).toThrow(/invalid database name 'ab'/);
        expect(() => collectionName("x".repeat(65))).toThrow(/invalid collection name/);
        expect(() => collectionName("a.b.c")).toThrow(TypeError);
        expect(() => databaseName(undefined)).toThrow(/'undefined'/);
    });

    test("ids take 1-64 characters", () => {
        expect(documentId("a")).toBe("a");
        expect(() => documentId("")).toThrow(/invalid _id/);
        expect(() => documentId("a b")).toThrow(TypeError);
        expect(() => documentId(5)).toThrow(TypeError);
    });
});
