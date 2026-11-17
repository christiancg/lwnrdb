using System;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization.Metadata;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Mapping;

public sealed class JsonCodec<T> : IDocumentCodec<T>
{
    private readonly JsonTypeInfo<T> _typeInfo;

    public JsonCodec(JsonSerializerOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        _typeInfo = (JsonTypeInfo<T>)options.GetTypeInfo(typeof(T));
        if (_typeInfo.Kind != JsonTypeInfoKind.Object)
        {
            throw new ArgumentException($"{typeof(T).Name} does not map to a JSON object, so it cannot be a document");
        }
        var id = _typeInfo.Properties.FirstOrDefault(property => property.Name == Document.IdField);
        if (id is not null && id.PropertyType != typeof(string))
        {
            throw new ArgumentException(
                $"{typeof(T).Name} maps a {id.PropertyType.Name} to _id; the server's ids are strings, so the member must be a string");
        }
    }

    public Document Encode(T value)
    {
        JsonNode? node;
        try
        {
            node = JsonSerializer.SerializeToNode(value, _typeInfo);
        }
        catch (JsonException error)
        {
            throw new MappingException($"{Where(error.Path)} {error.Message}", error);
        }
        if (node is not JsonObject document)
        {
            throw new MappingException($"{typeof(T).Name} does not map to a JSON object");
        }
        NumberGuard.CheckTree(document, string.Empty);
        return Document.Wrap(document);
    }

    public T Decode(Document document)
    {
        ArgumentNullException.ThrowIfNull(document);
        try
        {
            return document.Node.Deserialize(_typeInfo)
                ?? throw new MappingException($"document {document.Id ?? "without _id"} decoded to null");
        }
        catch (Exception error) when (error is JsonException or NotSupportedException or InvalidOperationException)
        {
            throw new MappingException(
                $"document {document.Id ?? "without _id"} does not map to {typeof(T).Name}: {error.Message}", error);
        }
    }

    private static string Where(string? path) =>
        path is null or "$" ? $"a {typeof(T).Name}:" : $"field '{path.TrimStart('$', '.')}' of {typeof(T).Name}:";
}
