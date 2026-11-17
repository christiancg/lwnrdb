using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client;

public sealed class Document : IEquatable<Document>
{
    public const string IdField = "_id";

    private Document(JsonObject node)
    {
        Node = node;
    }

    public static Document Empty { get; } = new(new JsonObject());

    public string? Id => Node[IdField] is JsonValue id && id.GetValueKind() == JsonValueKind.String ? id.GetValue<string>() : null;

    public IReadOnlyList<string> FieldNames => [.. Node.Select(entry => entry.Key)];

    internal JsonObject Node { get; }

    public static Document Of(params object?[] fieldsAndValues)
    {
        ArgumentNullException.ThrowIfNull(fieldsAndValues);
        if (fieldsAndValues.Length % 2 != 0)
        {
            throw new ArgumentException("Of takes field names and values in pairs", nameof(fieldsAndValues));
        }
        var node = new JsonObject();
        for (var index = 0; index < fieldsAndValues.Length; index += 2)
        {
            var field = fieldsAndValues[index] as string
                ?? throw new ArgumentException($"argument {index} must be a field name", nameof(fieldsAndValues));
            node[field] = DocumentValues.Encode(fieldsAndValues[index + 1], field);
        }
        return new Document(node);
    }

    public static Document From(IEnumerable<KeyValuePair<string, object?>> fields)
    {
        ArgumentNullException.ThrowIfNull(fields);
        var node = new JsonObject();
        foreach (var (field, value) in fields)
        {
            node[field] = DocumentValues.Encode(value, field);
        }
        return new Document(node);
    }

    public static Document Parse(string json)
    {
        ArgumentNullException.ThrowIfNull(json);
        try
        {
            return JsonNode.Parse(json) is JsonObject node
                ? new Document(node)
                : throw new MappingException("the JSON is not an object");
        }
        catch (JsonException error)
        {
            throw new MappingException($"not valid JSON: {error.Message}", error);
        }
    }

    public Document With(string field, object? value)
    {
        ArgumentNullException.ThrowIfNull(field);
        var node = (JsonObject)Node.DeepClone();
        node[field] = DocumentValues.Encode(value, field);
        return new Document(node);
    }

    public Document Without(string field)
    {
        ArgumentNullException.ThrowIfNull(field);
        var node = (JsonObject)Node.DeepClone();
        node.Remove(field);
        return new Document(node);
    }

    public bool Has(string path) => Resolve(path) is not null || ContainsNull(path);

    public object? Get(string path) => DocumentValues.Decode(Resolve(path));

    public string? GetString(string path) => Resolve(path) is JsonValue value && value.GetValueKind() == JsonValueKind.String
        ? value.GetValue<string>()
        : null;

    public double? GetDouble(string path) => JsonNumbers.Of(Resolve(path));

    public long? GetLong(string path) => GetDouble(path) is { } number && double.IsInteger(number) ? (long)number : null;

    public bool? GetBoolean(string path) => Resolve(path) is JsonValue value
        && value.GetValueKind() is JsonValueKind.True or JsonValueKind.False
        ? value.GetValue<bool>()
        : null;

    public DateTime? GetDateTime(string path) => Get(path) is DateTime value ? value : null;

    public TimeOnly? GetTimeOnly(string path) => Get(path) is TimeOnly value ? value : null;

    public GeoPoint? GetGeoPoint(string path) => Get(path) is GeoPoint value ? value : null;

    public Vector? GetVector(string path) => Get(path) as Vector;

    public Document? GetDocument(string path) => Resolve(path) is JsonObject node ? new Document((JsonObject)node.DeepClone()) : null;

    public IReadOnlyList<object?>? GetList(string path) => Get(path) as IReadOnlyList<object?>;

    public string ToJson() => Node.ToJsonString();

    public bool Equals(Document? other) => other is not null && JsonNode.DeepEquals(Node, other.Node);

    public override bool Equals(object? obj) => Equals(obj as Document);

    public override int GetHashCode() => ToJson().GetHashCode(StringComparison.Ordinal);

    public override string ToString() => ToJson();

    internal static Document Wrap(JsonObject node) => new(node);

    private JsonNode? Resolve(string path)
    {
        ArgumentNullException.ThrowIfNull(path);
        JsonNode? current = Node;
        foreach (var segment in path.Split('.'))
        {
            if (current is not JsonObject obj || !obj.TryGetPropertyValue(segment, out current))
            {
                return null;
            }
        }
        return current;
    }

    private bool ContainsNull(string path)
    {
        var lastDot = path.LastIndexOf('.');
        var parent = lastDot < 0 ? Node : Resolve(path[..lastDot]);
        return parent is JsonObject obj && obj.TryGetPropertyValue(path[(lastDot + 1)..], out var child) && child is null;
    }
}
