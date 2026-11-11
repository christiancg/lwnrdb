using System;
using System.Text.Json;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Mapping;

internal sealed class TimeOnlyConverter : JsonConverter<TimeOnly>
{
    public override TimeOnly Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        CustomReading.Read<TimeOnly>(ref reader, "a time");

    public override void Write(Utf8JsonWriter writer, TimeOnly value, JsonSerializerOptions options) =>
        writer.WriteStringValue(CustomValues.Format(value));
}
