import type { Document } from "./document.js";

export interface SaveResult {
    readonly id: string | undefined;
    readonly inserted: boolean;
}

export interface BulkSaveResult {
    readonly inserted: readonly string[];
    readonly updated: readonly string[];
}

export interface ReindexResult {
    readonly rebuiltFields: readonly string[];
    readonly primaryKeyEntries: number | undefined;
    readonly unversionedEntries: number | undefined;
}

export interface ScriptResult {
    readonly value: unknown;
    readonly logs: readonly string[];
    readonly logsTruncated: boolean;
    readonly runId: string | undefined;
}

export interface AnalyzeResult {
    readonly results: readonly Document[];
    readonly analysis: Document;
}

export interface ReadOptions {
    readonly dirtyRead?: boolean;
}
