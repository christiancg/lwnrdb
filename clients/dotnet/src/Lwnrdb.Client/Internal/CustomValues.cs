using System;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Internal;

internal static partial class CustomValues
{
    public const string DetectionRegex = "^#[a-zA-Z0-9]{3,20}\\(.*\\)$";
    private const char Separator = ',';

    public static bool LooksCustom(string text) =>
        text.Length > 0 && text[0] == '#' && Detection().IsMatch(text);

    public static string FormatComponent(double value) => value.ToString("R", CultureInfo.InvariantCulture);

    public static string Format(GeoPoint point) =>
        Wrap(CustomType.Geo, FormatComponent(point.Latitude) + Separator + FormatComponent(point.Longitude));

    public static string Format(Vector vector) =>
        Wrap(CustomType.Vector, string.Join(Separator, vector.ToArray().Select(FormatComponent)));

    public static string Format(DateTime value) => Wrap(CustomType.DateTime, TemporalText.Format(value));

    public static string Format(TimeOnly value) => Wrap(CustomType.Time, TemporalText.Format(value));

    public static string FormatVector(ReadOnlySpan<double> components)
    {
        var parts = new string[components.Length];
        for (var index = 0; index < components.Length; index++)
        {
            parts[index] = FormatComponent(components[index]);
        }
        return Wrap(CustomType.Vector, string.Join(Separator, parts));
    }

    public static object Parse(string text)
    {
        var nameEnd = text.IndexOf('(', StringComparison.Ordinal);
        var name = text[1..nameEnd];
        var body = text[(nameEnd + 1)..^1];
        try
        {
            return name switch
            {
                "datetime" => TemporalText.ParseDateTime(body),
                "time" => TemporalText.ParseTime(body),
                "geo" => ParseGeo(body),
                "vector" => Vector.Of(ParseComponents(body)),
                _ => text,
            };
        }
        catch (Exception error) when (error is FormatException or ArgumentException or OverflowException)
        {
            throw new MappingException($"the server sent a malformed {name} value: {text}", error);
        }
    }

    public static T ParseAs<T>(string text, string label)
    {
        if (!LooksCustom(text) || Parse(text) is not T parsed)
        {
            throw new MappingException($"{label} is not a {typeof(T).Name}: {text}");
        }
        return parsed;
    }

    private static GeoPoint ParseGeo(string body)
    {
        var components = ParseComponents(body);
        if (components.Length != 2)
        {
            throw new FormatException("a geo value holds two coordinates");
        }
        return new GeoPoint(components[0], components[1]);
    }

    private static double[] ParseComponents(string body) =>
        [.. body.Split(Separator).Select(part => double.Parse(part.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture))];

    private static string Wrap(CustomType type, string body) => "#" + WireNames.Of(type) + "(" + body + ")";

    [GeneratedRegex(DetectionRegex, RegexOptions.CultureInvariant | RegexOptions.Singleline)]
    private static partial Regex Detection();
}
