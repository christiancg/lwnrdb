using System;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Mapping;

internal static class VectorArrayConverter
{
    public static JsonConverter For(Type type)
    {
        if (type == typeof(double[]))
        {
            return new DoubleArrayConverter();
        }
        if (type == typeof(float[]))
        {
            return new FloatArrayConverter();
        }
        throw new InvalidOperationException($"[LwnrdbVector] applies to double[] and float[] members, not {type.Name}");
    }

    private static string Format(ReadOnlySpan<double> components)
    {
        if (components.IsEmpty)
        {
            throw new JsonException("a vector needs at least one component");
        }
        foreach (var component in components)
        {
            if (!double.IsFinite(component))
            {
                throw new JsonException("a vector component is not finite");
            }
        }
        return CustomValues.FormatVector(components);
    }

    private sealed class DoubleArrayConverter : JsonConverter<double[]>
    {
        public override double[]? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
            CustomReading.Read<Vector>(ref reader, "a vector").ToArray();

        public override void Write(Utf8JsonWriter writer, double[] value, JsonSerializerOptions options) =>
            writer.WriteStringValue(Format(value));
    }

    private sealed class FloatArrayConverter : JsonConverter<float[]>
    {
        public override float[]? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
            [.. CustomReading.Read<Vector>(ref reader, "a vector").ToArray().Select(component => (float)component)];

        public override void Write(Utf8JsonWriter writer, float[] value, JsonSerializerOptions options) =>
            writer.WriteStringValue(Format([.. value.Select(component => (double)component)]));
    }
}
