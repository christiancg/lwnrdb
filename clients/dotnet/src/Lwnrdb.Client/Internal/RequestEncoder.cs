using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal;

internal sealed class RequestEncoder(TimeZoneInfo? temporalZone)
{
    public PipelineEncoder Pipeline { get; } = new(temporalZone);

    public TimeZoneInfo? TemporalZone { get; } = temporalZone;

    public static JsonObject Request(string type, string? database = null, string? collection = null)
    {
        var frame = new JsonObject { ["type"] = type };
        if (database is not null)
        {
            frame["databaseName"] = database;
        }
        if (collection is not null)
        {
            frame["collectionName"] = collection;
        }
        return frame;
    }

    public static JsonArray Strings(IEnumerable<string> values) => [.. values.Select(value => (JsonNode)value)];

    public static JsonObject Document(Document document) => (JsonObject)document.Node.DeepClone();

    public static JsonObject Authenticate(string username, string password)
    {
        var frame = Request("AUTHENTICATE");
        frame["username"] = username;
        frame["password"] = password;
        return frame;
    }

    public static JsonObject Save(string database, string collection, JsonObject document) =>
        With(Request("SAVE", database, collection), "object", document);

    public static JsonObject BulkSave(string database, string collection, IEnumerable<JsonObject> documents) =>
        With(Request("BULK_SAVE", database, collection), "objects", new JsonArray([.. documents]));

    public static JsonObject FindById(string database, string collection, string id, bool dirtyRead) =>
        Dirty(With(Request("FIND_BY_ID", database, collection), "_id", id), dirtyRead);

    public static JsonObject Delete(string database, string collection, string id) =>
        With(Request("DELETE", database, collection), "_id", id);

    public JsonObject DeleteWhere(string database, string collection, Filter condition) =>
        With(Request("DELETE", database, collection), "condition", Pipeline.Filter(condition));

    public JsonObject Aggregate(string database, string collection, ImmutableArray<PipelineStep> steps, bool dirtyRead, bool analyze)
    {
        var frame = With(Request("AGGREGATE", database, collection), "aggregationSteps", Pipeline.Steps(steps));
        if (analyze)
        {
            frame["analyze"] = true;
        }
        return Dirty(frame, dirtyRead);
    }

    public JsonObject Listen(string database, string collection, ImmutableArray<PipelineStep> steps) =>
        With(Request("LISTEN", database, collection), "aggregationSteps", Pipeline.Steps(steps));

    public static JsonObject StopListen(string listenId) => With(Request("STOP_LISTEN"), "listenId", listenId);

    public static JsonObject CreateIndex(string database, string collection, IReadOnlyList<string> fields)
    {
        var frame = Request("CREATE_INDEX", database, collection);
        return fields.Count == 1 ? With(frame, "fieldName", fields[0]) : With(frame, "fieldNames", Strings(fields));
    }

    public static JsonObject DropIndex(string database, string collection, string field) =>
        With(Request("DROP_INDEX", database, collection), "fieldName", field);

    public static JsonObject Reindex(string database, string collection, IReadOnlyList<string> fields)
    {
        var frame = Request("REINDEX", database, collection);
        return fields.Count == 0 ? frame : With(frame, "fieldNames", Strings(fields));
    }

    public static JsonObject ReindexPrimaryKey(string database, string collection) =>
        With(Request("REINDEX", database, collection), "primaryKey", true);

    public static JsonObject SaveSchema(string database, string collection, JsonObject schema) =>
        With(Request("SAVE_SCHEMA", database, collection), "schema", schema);

    public static JsonObject SetDatabaseOwners(string database, IEnumerable<string> owners) =>
        With(Request("SET_DATABASE_OWNERS", database), "owners", Strings(owners));

    public static JsonObject With(JsonObject frame, string field, JsonNode? value)
    {
        frame[field] = value;
        return frame;
    }

    private static JsonObject Dirty(JsonObject frame, bool dirtyRead) => dirtyRead ? With(frame, "dirtyRead", true) : frame;
}
