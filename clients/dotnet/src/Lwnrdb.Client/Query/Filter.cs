using System;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Query;

public abstract record Filter;

public sealed record FieldFilter(FieldOperator Operator, string Field, JsonNode? Value) : Filter
{
    public bool Equals(FieldFilter? other) =>
        other is not null
        && Operator == other.Operator
        && string.Equals(Field, other.Field, StringComparison.Ordinal)
        && JsonNode.DeepEquals(Value, other.Value);

    public override int GetHashCode() => HashCode.Combine(Operator, Field);
}

public sealed record ConjunctionFilter(Conjunction Conjunction, ImmutableArray<Filter> Operands) : Filter
{
    public bool Equals(ConjunctionFilter? other) =>
        other is not null && Conjunction == other.Conjunction && Operands.SequenceEqual(other.Operands);

    public override int GetHashCode() => HashCode.Combine(Conjunction, Operands.Length);
}

public sealed record DistanceFilter(string Field, GeoPoint Target, GeoDistanceComparator Comparator, double Metres) : Filter;

public sealed record WithinFilter(string Field, ImmutableArray<GeoPoint> Polygon) : Filter
{
    public bool Equals(WithinFilter? other) =>
        other is not null && string.Equals(Field, other.Field, StringComparison.Ordinal) && Polygon.SequenceEqual(other.Polygon);

    public override int GetHashCode() => HashCode.Combine(Field, Polygon.Length);
}

public sealed record NearestFilter(string Field, Vector Query, int K, bool IsExact = false) : Filter
{
    public NearestFilter Exact() => this with { IsExact = true };
}

public sealed record ScriptFilter(string Source) : Filter;
