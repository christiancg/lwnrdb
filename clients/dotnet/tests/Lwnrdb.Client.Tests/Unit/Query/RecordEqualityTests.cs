using System.Collections.Immutable;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.Tests.Unit.Query;

public sealed class RecordEqualityTests
{
    [Fact]
    public void FiltersCompareByValue()
    {
        Assert.NotEqual(Field("a").Eq(1), Field("a").Eq(2));
        Assert.NotEqual(Field("a").Eq(1), Field("b").Eq(1));
        Assert.NotEqual(Field("a").Eq(1), Field("a").Ne(1));
        Assert.False(new FieldFilter(FieldOperator.Equal, "a", null).Equals(null));
        Assert.Equal(Field("a").Eq(1).GetHashCode(), Field("a").Eq(1).GetHashCode());
        Assert.NotEqual(And(Field("a").Eq(1)), Or(Field("a").Eq(1)));
        Assert.NotEqual(And(Field("a").Eq(1)), And(Field("a").Eq(2)));
        Assert.False(((ConjunctionFilter)And(Field("a").Eq(1))).Equals(null));
        Assert.Equal(And(Field("a").Eq(1)).GetHashCode(), And(Field("a").Eq(1)).GetHashCode());
        var square = new[] { new GeoPoint(0, 0), new GeoPoint(1, 0), new GeoPoint(1, 1) };
        Assert.Equal(Within("p", square), Within("p", square));
        Assert.NotEqual(Within("p", square), Within("q", square));
        Assert.NotEqual(Within("p", square), Within("p", new GeoPoint(0, 0), new GeoPoint(2, 0), new GeoPoint(1, 1)));
        Assert.False(((WithinFilter)Within("p", square)).Equals(null));
        Assert.Equal(Within("p", square).GetHashCode(), Within("p", square).GetHashCode());
    }

    [Fact]
    public void StepsAndOperationsCompareByValue()
    {
        var map = new MapStep([MapOps.Remove("a")]);
        Assert.Equal(map, new MapStep([MapOps.Remove("a")]));
        Assert.NotEqual(map, new MapStep([MapOps.Remove("b")]));
        Assert.False(map.Equals(null));
        Assert.Equal(map.GetHashCode(), new MapStep([MapOps.Remove("a")]).GetHashCode());
        var reduce = new ReduceStep("s", true, JsonValue.Create(1), "r");
        Assert.Equal(reduce, new ReduceStep("s", true, JsonValue.Create(1), "r"));
        Assert.NotEqual(reduce, reduce with { Script = "t" });
        Assert.NotEqual(reduce, reduce with { HasInitialValue = false });
        Assert.NotEqual(reduce, reduce with { InitialValue = JsonValue.Create(2) });
        Assert.NotEqual(reduce, reduce with { ResultField = "x" });
        Assert.False(reduce.Equals(null));
        Assert.Equal(reduce.GetHashCode(), new ReduceStep("s", true, JsonValue.Create(1), "r").GetHashCode());
        var sum = (ArrayOperation)MidOps.Sum("a", 1);
        Assert.Equal(sum, MidOps.Sum("a", 1));
        Assert.NotEqual(sum, MidOps.Avg("a", 1));
        Assert.NotEqual(sum, MidOps.Sum("a", 2));
        Assert.False(sum.Equals(null));
        Assert.Equal(sum.GetHashCode(), MidOps.Sum("a", 1).GetHashCode());
        Assert.Equal(ImmutableArray.Create<object?>("a", 1), sum.Operands);
    }
}
