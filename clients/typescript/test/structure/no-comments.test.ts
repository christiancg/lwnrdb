import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import ts from "typescript";
import { expect, test } from "vitest";

const ROOT = join(import.meta.dirname, "..", "..");
const SCANNED_FOLDERS = ["src", "test", "build-tools", "consumers"];
const SKIPPED_FOLDERS = new Set(["node_modules", "dist", "coverage"]);
const SOURCE_EXTENSIONS = [".ts", ".js", ".mjs", ".cjs"];
const DIRECTIVES = [/^\/\/\s*eslint-disable/, /^\/\*\s*eslint-disable/, /^\/\/\s*@ts-expect-error/];

function sourceFiles(folder: string): string[] {
    return readdirSync(folder).flatMap((entry) => {
        const path = join(folder, entry);
        if (statSync(path).isDirectory()) {
            return SKIPPED_FOLDERS.has(entry) ? [] : sourceFiles(path);
        }
        return SOURCE_EXTENSIONS.some((extension) => entry.endsWith(extension)) ? [path] : [];
    });
}

function rootConfigFiles(): string[] {
    return readdirSync(ROOT)
        .filter((entry) => /\.config\.(ts|js)$/.test(entry))
        .map((entry) => join(ROOT, entry));
}

function commentRanges(source: ts.SourceFile): Map<number, ts.CommentRange> {
    const text = source.getFullText();
    const ranges = new Map<number, ts.CommentRange>();
    const collect = (found: ts.CommentRange[] | undefined): void => {
        found?.forEach((range) => ranges.set(range.pos, range));
    };
    const visit = (node: ts.Node): void => {
        collect(ts.getLeadingCommentRanges(text, node.getFullStart()));
        collect(ts.getTrailingCommentRanges(text, node.getEnd()));
        node.getChildren(source).forEach(visit);
    };
    visit(source);
    return ranges;
}

function commentsIn(path: string): string[] {
    const text = readFileSync(path, "utf8");
    const source = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true);
    return [...commentRanges(source).values()]
        .map((range) => ({ range, comment: text.slice(range.pos, range.end) }))
        .filter(({ comment }) => !DIRECTIVES.some((directive) => directive.test(comment)))
        .map(({ range, comment }) => {
            const line = source.getLineAndCharacterOfPosition(range.pos).line + 1;
            return `${relative(ROOT, path)}:${line} ${comment.split("\n")[0] ?? ""}`;
        });
}

test("no source file holds a comment other than a toolchain directive", () => {
    const files = [...SCANNED_FOLDERS.flatMap((folder) => sourceFiles(join(ROOT, folder))), ...rootConfigFiles()];
    expect(files.length).toBeGreaterThan(0);
    expect(files.flatMap(commentsIn)).toEqual([]);
});
