using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Admin;
using Lwnrdb.Client.Scripting;

namespace Lwnrdb.Client.Internal;

internal static class Descriptors
{
    public static ProcedureInfo Procedure(Document row) => new(
        Text(row, "name"),
        Whole(row, "version"),
        row.GetBoolean("enabled") ?? true,
        row.GetString("description"),
        row.GetString("source"),
        row);

    public static TriggerInfo Trigger(Document row) => new(
        Text(row, "name"),
        row.GetString("collection"),
        List(row, "events"),
        Text(row, "procedureName"),
        row.GetString("timing") ?? "after",
        row.GetString("mode") ?? "document",
        row.GetBoolean("enabled") ?? true,
        Whole(row, "version"),
        row.GetString("definer"),
        row);

    public static ScheduleInfo Schedule(Document row) => new(
        Text(row, "name"),
        Text(row, "procedureName"),
        row.GetString("cron"),
        row.GetDouble("intervalMs") is { } interval ? TimeSpan.FromMilliseconds(interval) : null,
        row.GetBoolean("enabled") ?? true,
        Whole(row, "version"),
        row.GetString("definer"),
        row.GetDouble("nextRunAt") is { } next ? DateTimeOffset.FromUnixTimeMilliseconds((long)next) : null,
        row);

    public static UserInfo User(Document row) => new(
        Text(row, "_id"),
        row.GetBoolean("admin") ?? false,
        List(row, "globalPermissions"),
        row.GetDocument("databasePermissions") ?? Document.Empty,
        row.GetDocument("collectionPermissions") ?? Document.Empty,
        row.GetDocument("scriptPermissions") ?? Document.Empty,
        List(row, "ownedDatabases"),
        row);

    public static TransactionInfo Transaction(Document row) => new(
        Text(row, "dtxId"),
        row.GetString("coordinator"),
        row.GetBoolean("coordinatorReachable") ?? false,
        List(row, "participants"),
        Whole(row, "ageMs"),
        row);

    public static ScriptRun ScriptRun(Document row) => new(
        Text(row, "runId"),
        row.GetString("node"),
        Text(row, "kind"),
        row.GetString("database"),
        row.GetString("name"),
        row.GetString("username"),
        Whole(row, "ageMs"),
        row);

    public static TriggerRun TriggerRun(Document row) => new(
        Text(row, "runId"),
        Text(row, "status"),
        row.GetString("database"),
        row.GetString("collection"),
        row.GetString("trigger"),
        row.GetString("procedure"),
        row.GetString("event"),
        Whole(row, "attempts"),
        row.GetString("lastError"),
        row);

    public static ServerInfo Server(Document row) => new(row.GetString("state"), row.GetString("version"), row);

    public static ScriptResult ScriptResult(JsonObject reply) => new(
        reply.TryGetPropertyValue("result", out var result) ? DocumentValues.Decode(result) : null,
        ResponseDecoder.Strings(reply["logs"]),
        ResponseDecoder.BooleanOf(reply["logsTruncated"], false),
        ResponseDecoder.TextOf(reply["runId"]));

    private static string Text(Document row, string field) => row.GetString(field) ?? string.Empty;

    private static long Whole(Document row, string field) => row.GetDouble(field) is { } number ? (long)number : 0;

    private static IReadOnlyList<string> List(Document row, string field) =>
        row.Node[field] is JsonArray array ? [.. array.Select(item => ResponseDecoder.TextOf(item) ?? "null")] : [];
}
