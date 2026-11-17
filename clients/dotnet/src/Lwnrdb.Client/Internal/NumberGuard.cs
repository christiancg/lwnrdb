using System;
using System.Globalization;
using System.Numerics;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Errors;

namespace Lwnrdb.Client.Internal;

internal static class NumberGuard
{
    private static readonly BigInteger MaxSafeInteger = (BigInteger.One << 53) - 1;

    public static string Label(string path) => path.Length == 0 ? "the value" : $"field '{path}'";

    public static double Check(double value, string path)
    {
        if (!double.IsFinite(value))
        {
            throw new MappingException($"{Label(path)} is not a finite number: {value.ToString(CultureInfo.InvariantCulture)}");
        }
        if (double.IsInteger(value) && Math.Abs(value) > (double)MaxSafeInteger)
        {
            throw TooLarge(path);
        }
        return value;
    }

    public static void CheckTree(JsonNode? node, string path)
    {
        switch (node)
        {
            case JsonObject obj:
                foreach (var (key, child) in obj)
                {
                    CheckTree(child, path.Length == 0 ? key : path + "." + key);
                }
                return;
            case JsonArray array:
                for (var index = 0; index < array.Count; index++)
                {
                    CheckTree(array[index], path + "[" + index.ToString(CultureInfo.InvariantCulture) + "]");
                }
                return;
            case JsonValue value when value.GetValueKind() == JsonValueKind.Number:
                CheckNumberText(value.ToJsonString(), path);
                return;
            default:
                return;
        }
    }

    private static void CheckNumberText(string text, string path)
    {
        if (text.AsSpan().IndexOfAny('.', 'e', 'E') >= 0)
        {
            Check(double.Parse(text, NumberStyles.Float, CultureInfo.InvariantCulture), path);
            return;
        }
        if (BigInteger.Abs(BigInteger.Parse(text, CultureInfo.InvariantCulture)) > MaxSafeInteger)
        {
            throw TooLarge(path);
        }
    }

    private static MappingException TooLarge(string path) =>
        new($"{Label(path)} exceeds 2^53, which the server stores as a double; store it as a string");
}
