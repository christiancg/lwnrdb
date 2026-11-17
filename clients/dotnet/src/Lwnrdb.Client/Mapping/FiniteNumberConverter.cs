using System;
using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Lwnrdb.Client.Mapping;

internal sealed class FiniteDoubleConverter : JsonConverter<double>
{
    public override double Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.GetDouble();

    public override void Write(Utf8JsonWriter writer, double value, JsonSerializerOptions options) =>
        writer.WriteNumberValue(FiniteNumbers.Checked(value));
}

internal sealed class FiniteSingleConverter : JsonConverter<float>
{
    public override float Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.GetSingle();

    public override void Write(Utf8JsonWriter writer, float value, JsonSerializerOptions options) =>
        writer.WriteNumberValue((float)FiniteNumbers.Checked(value));
}

internal static class FiniteNumbers
{
    public static double Checked(double value) =>
        double.IsFinite(value)
            ? value
            : throw new JsonException($"{value.ToString(CultureInfo.InvariantCulture)} is not a finite number, which JSON cannot hold");
}
