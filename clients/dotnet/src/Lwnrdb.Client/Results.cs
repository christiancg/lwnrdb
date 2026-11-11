using System.Collections.Generic;

namespace Lwnrdb.Client;

public sealed record SaveResult(string? Id, bool Inserted);

public sealed record BulkSaveResult(IReadOnlyList<string> Inserted, IReadOnlyList<string> Updated);

public sealed record ReindexResult(IReadOnlyList<string> RebuiltFields, long? PrimaryKeyEntries, long? UnversionedEntries);

public sealed record ScriptResult(object? Value, IReadOnlyList<string> Logs, bool LogsTruncated, string? RunId);

public sealed record AnalyzeResult(IReadOnlyList<Document> Results, Document Analysis);
