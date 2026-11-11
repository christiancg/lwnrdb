using System;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Linq;

public static class LwnrdbFunctions
{
    public static double Distance(GeoPoint field, GeoPoint target) => throw OnlyInQueries(nameof(Distance));

    public static bool Within(GeoPoint field, params GeoPoint[] polygon) => throw OnlyInQueries(nameof(Within));

    public static bool Script(string source) => throw OnlyInQueries(nameof(Script));

    private static InvalidOperationException OnlyInQueries(string name) =>
        new($"LwnrdbFunctions.{name} only has a meaning inside a LWNRDB LINQ query, which the server evaluates");
}
