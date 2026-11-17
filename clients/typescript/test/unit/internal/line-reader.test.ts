import { describe, expect, test } from "vitest";
import { LineReader } from "../../../src/internal/line-reader.js";

function lines(reader: LineReader, ...chunks: (string | Buffer)[]): unknown[] {
    return chunks.flatMap((chunk) => reader.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk));
}

describe("LineReader", () => {
    test("joins a line split across chunks and splits several lines in one chunk", () => {
        const reader = new LineReader(1024);
        expect(lines(reader, '{"a":', '1}\n{"b":2}\n{"c"', ":3}\n")).toEqual([
            { tooLarge: false, text: '{"a":1}' },
            { tooLarge: false, text: '{"b":2}' },
            { tooLarge: false, text: '{"c":3}' },
        ]);
    });

    test("decodes a multi-byte character split across chunks", () => {
        const bytes = Buffer.from("ñandú\n");
        const reader = new LineReader(1024);
        expect(lines(reader, bytes.subarray(0, 2), bytes.subarray(2, 6), bytes.subarray(6))).toEqual([
            { tooLarge: false, text: "ñandú" },
        ]);
    });

    test("strips a carriage return and reports empty lines", () => {
        expect(lines(new LineReader(16), "a\r\n\n")).toEqual([
            { tooLarge: false, text: "a" },
            { tooLarge: false, text: "" },
        ]);
    });

    test("reports a line over the cap once and keeps reading the next one", () => {
        const reader = new LineReader(4);
        expect(lines(reader, "abc", "def", "ghi\nok\n", "abcd\n")).toEqual([
            { tooLarge: true },
            { tooLarge: false, text: "ok" },
            { tooLarge: false, text: "abcd" },
        ]);
    });
});
