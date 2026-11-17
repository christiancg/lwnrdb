using System;
using System.Buffers;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Errors;

namespace Lwnrdb.Client.Internal.Linq;

internal static class ConstantEncoder
{
    private static readonly MethodInfo WriteWithMethod =
        typeof(ConstantEncoder).GetMethod(nameof(WriteWith), BindingFlags.NonPublic | BindingFlags.Static)!;

    private static readonly MethodInfo ReadWithMethod =
        typeof(ConstantEncoder).GetMethod(nameof(ReadWith), BindingFlags.NonPublic | BindingFlags.Static)!;

    public static JsonNode? Encode(object? value, MemberTarget target, JsonSerializerOptions options)
    {
        if (value is null)
        {
            return null;
        }
        var declared = Nullable.GetUnderlyingType(target.ValueType) ?? target.ValueType;
        if (declared.IsEnum && value is not Enum)
        {
            value = Enum.ToObject(declared, value);
        }
        try
        {
            var converter = ConverterOf(target, declared, options);
            var node = converter is null
                ? JsonSerializer.SerializeToNode(value, SerializedType(value, declared), options)
                : (JsonNode?)WriteWithMethod.MakeGenericMethod(converter.Type!).Invoke(null, [converter, value, options]);
            NumberGuard.CheckTree(node, target.Path);
            return node;
        }
        catch (TargetInvocationException error) when (error.InnerException is JsonException json)
        {
            throw Mapping(target, json);
        }
        catch (JsonException error)
        {
            throw Mapping(target, error);
        }
    }

    public static JsonNode? EncodeElement(object? value, Type elementType, string path, JsonSerializerOptions options) =>
        Encode(value, new MemberTarget(path, elementType, null), options);

    public static object? Decode(JsonNode? node, MemberTarget target, JsonSerializerOptions options)
    {
        if (node is null)
        {
            return null;
        }
        var declared = Nullable.GetUnderlyingType(target.ValueType) ?? target.ValueType;
        var converter = ConverterOf(target, declared, options);
        return converter is null
            ? node.Deserialize(target.ValueType, options)
            : ReadWithMethod.MakeGenericMethod(converter.Type!).Invoke(null, [converter, node, options]);
    }

    private static JsonConverter? ConverterOf(MemberTarget target, Type declared, JsonSerializerOptions options) =>
        target.Property?.CustomConverter switch
        {
            null => null,
            JsonConverterFactory factory => factory.CreateConverter(declared, options),
            var converter => converter,
        };

    private static Type SerializedType(object value, Type declared) =>
        declared != typeof(object) && declared.IsInstanceOfType(value) ? declared : value.GetType();

    private static MappingException Mapping(MemberTarget target, JsonException error) =>
        new($"the value compared with '{target.Path}' does not map to JSON: {error.Message}", error);

    private static JsonNode? WriteWith<TValue>(JsonConverter<TValue> converter, TValue value, JsonSerializerOptions options)
    {
        var buffer = new ArrayBufferWriter<byte>();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            converter.Write(writer, value, options);
        }
        return JsonNode.Parse(buffer.WrittenSpan);
    }

    private static TValue? ReadWith<TValue>(JsonConverter<TValue> converter, JsonNode node, JsonSerializerOptions options)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(node);
        var reader = new Utf8JsonReader(bytes);
        reader.Read();
        return converter.Read(ref reader, typeof(TValue), options);
    }
}
