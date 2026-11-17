using System;
using System.Text.Json;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Mapping;

internal sealed class GuardedStringConverter : JsonConverter<string>
{
    public override bool HandleNull => false;

    public override string? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.TokenType == JsonTokenType.String
            ? reader.GetString()
            : throw new JsonException($"expected a string, found {reader.TokenType}");

    public override void Write(Utf8JsonWriter writer, string value, JsonSerializerOptions options)
    {
        if (CustomValues.LooksCustom(value))
        {
            throw new JsonException(
                $"the text '{value}' looks like a custom value; the server would read it as one, so it cannot be stored as text");
        }
        writer.WriteStringValue(value);
    }

    public override string ReadAsPropertyName(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.GetString()!;

    public override void WriteAsPropertyName(Utf8JsonWriter writer, string value, JsonSerializerOptions options) =>
        writer.WritePropertyName(value);
}
