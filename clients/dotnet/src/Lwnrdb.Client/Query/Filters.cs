using System;
using System.Collections.Immutable;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Query;

public static class Filters
{
    private const int MinPolygonPoints = 3;

    public static FieldCondition Field(string name) => new(name);

    public static Filter And(params Filter[] operands) => Conjunction(Query.Conjunction.And, operands);

    public static Filter Or(params Filter[] operands) => Conjunction(Query.Conjunction.Or, operands);

    public static Filter Nor(params Filter[] operands) => Conjunction(Query.Conjunction.Nor, operands);

    public static Filter Xor(params Filter[] operands) => Conjunction(Query.Conjunction.Xor, operands);

    public static Filter Nand(params Filter[] operands) => Conjunction(Query.Conjunction.Nand, operands);

    public static Filter Distance(string field, GeoPoint target, GeoDistanceComparator comparator, double metres)
    {
        if (!double.IsFinite(metres) || metres < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(metres), metres, "distance must be a finite, non-negative number of metres");
        }
        return new DistanceFilter(Checks.Text(field, nameof(field)), target, comparator, metres);
    }

    public static Filter Within(string field, params GeoPoint[] polygon)
    {
        ArgumentNullException.ThrowIfNull(polygon);
        if (polygon.Length < MinPolygonPoints)
        {
            throw new ArgumentException($"a within polygon needs at least 3 points, got {polygon.Length}", nameof(polygon));
        }
        return new WithinFilter(Checks.Text(field, nameof(field)), [.. polygon]);
    }

    public static NearestFilter Nearest(string field, Vector query, int k)
    {
        ArgumentNullException.ThrowIfNull(query);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(k);
        return new NearestFilter(Checks.Text(field, nameof(field)), query, k);
    }

    public static Filter Script(string source) => new ScriptFilter(Checks.Text(source, nameof(source)));

    private static ConjunctionFilter Conjunction(Conjunction conjunction, Filter[] operands)
    {
        ArgumentNullException.ThrowIfNull(operands);
        if (operands.Length == 0)
        {
            throw new ArgumentException($"{conjunction} needs at least one operand", nameof(operands));
        }
        foreach (var operand in operands)
        {
            ArgumentNullException.ThrowIfNull(operand, nameof(operands));
        }
        return new ConjunctionFilter(conjunction, ImmutableArray.Create(operands));
    }
}
