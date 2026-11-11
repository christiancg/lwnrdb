using System;
using System.Text.Json;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Mapping;

internal sealed class GeoPointConverter : JsonConverter<GeoPoint>
{
    public override GeoPoint Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        CustomReading.Read<GeoPoint>(ref reader, "a geo point");

    public override void Write(Utf8JsonWriter writer, GeoPoint value, JsonSerializerOptions options) =>
        writer.WriteStringValue(CustomValues.Format(value));
}
