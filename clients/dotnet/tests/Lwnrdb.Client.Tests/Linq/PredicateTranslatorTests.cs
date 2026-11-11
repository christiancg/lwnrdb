using System;
using System.Collections.Generic;
using System.Linq;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class PredicateTranslatorTests
{
    private readonly LinqHarness _harness = new();

    [Fact]
    public void EachComparisonTranslatesWithTheMemberOnEitherSide()
    {
        Assert.Equal(Field("total").Eq(5), _harness.Filter(o => o.Total == 5));
        Assert.Equal(Field("total").Ne(5), _harness.Filter(o => 5 != o.Total));
        Assert.Equal(Field("total").Gt(5), _harness.Filter(o => o.Total > 5));
        Assert.Equal(Field("total").Gt(5), _harness.Filter(o => 5 < o.Total));
        Assert.Equal(Field("total").Gte(5), _harness.Filter(o => 5 <= o.Total));
        Assert.Equal(Field("total").Lt(5), _harness.Filter(o => 5 > o.Total));
        Assert.Equal(Field("total").Lte(5), _harness.Filter(o => o.Total <= 5));
    }

    [Fact]
    public void ConjunctionsFlattenTheirChains()
    {
        var expected = And(Field("total").Gt(1), Field("total").Lt(9), Field("name").Eq("a"));
        Assert.Equal(expected, _harness.Filter(o => o.Total > 1 && o.Total < 9 && o.Name == "a"));
        Assert.Equal(Or(Field("total").Gt(1), Field("total").Lt(0), Field("paid").Eq(true)), _harness.Filter(o => o.Total > 1 || o.Total < 0 || o.Paid));
        Assert.Equal(Xor(Field("paid").Eq(true), Field("admin").Eq(true)), _harness.Filter(o => o.Paid ^ o.Admin));
    }

    [Fact]
    public void NegationsFollowTheSpellingsCSharpTreatsAsIdentical()
    {
        Assert.Equal(Nand(Field("total").Gt(1), Field("paid").Eq(true)), _harness.Filter(o => !(o.Total > 1 && o.Paid)));
        Assert.Equal(Nor(Field("total").Gt(1), Field("paid").Eq(true)), _harness.Filter(o => !(o.Total > 1 || o.Paid)));
        Assert.Equal(Nor(Field("total").Lt(5)), _harness.Filter(o => !(o.Total < 5)));
        Assert.Equal(_harness.Filter(o => o.Name != "a"), _harness.Filter(o => !(o.Name == "a")));
        Assert.Equal(_harness.Filter(o => o.Name == "a"), _harness.Filter(o => !(o.Name != "a")));
        Assert.Equal(_harness.Filter(o => o.Paid == false), _harness.Filter(o => !o.Paid));
        Assert.Equal(Field("paid").Eq(true), _harness.Filter(o => o.Paid));
        Assert.Equal(Nor(Field("tags").Contains("vip")), _harness.Filter(o => !o.Tags.Contains("vip")));
    }

    [Fact]
    public void NullChecksAndHasValueCompareWithNull()
    {
        Assert.Equal(Field("deletedAt").Eq(null), _harness.Filter(o => o.DeletedAt == null));
        Assert.Equal(Field("deletedAt").Ne(null), _harness.Filter(o => o.DeletedAt != null));
        Assert.Equal(Field("deletedAt").Ne(null), _harness.Filter(o => o.DeletedAt.HasValue));
        Assert.Equal(Field("deletedAt").Eq(null), _harness.Filter(o => !o.DeletedAt.HasValue));
    }

    [Fact]
    public void ContainsOnACapturedCollectionIsInAndNegatedIsNotIn()
    {
        var array = new[] { "a", "b" };
        var list = new List<string> { "a", "b" };
        var set = new HashSet<string> { "a", "b" };
        var expected = Field("name").In("a", "b");
        Assert.Equal(expected, _harness.Filter(o => array.Contains(o.Name)));
        Assert.Equal(expected, _harness.Filter(o => list.Contains(o.Name!)));
        Assert.Equal(expected, _harness.Filter(o => Enumerable.Contains(list, o.Name)));
        Assert.Equal(Field("name").In("a", "b"), _harness.Filter(o => set.Contains(o.Name!)));
        Assert.Equal(Field("name").NotIn("a", "b"), _harness.Filter(o => !array.Contains(o.Name)));
    }

    [Fact]
    public void ContainsOnAStoredMemberIsContains()
    {
        Assert.Equal(Field("tags").Contains("vip"), _harness.Filter(o => o.Tags.Contains("vip")));
        Assert.Equal(Field("name").Contains("an"), _harness.Filter(o => o.Name!.Contains("an")));
        Assert.Equal(Field("name").Contains("an"), _harness.Filter(o => o.Name!.Contains("an", StringComparison.Ordinal)));
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => o.Name!.Contains("an", StringComparison.OrdinalIgnoreCase)));
    }

    [Fact]
    public void EqualsCallsTranslateLikeEquality()
    {
        Assert.Equal(Field("name").Eq("a"), _harness.Filter(o => o.Name!.Equals("a")));
        Assert.Equal(Field("name").Eq("a"), _harness.Filter(o => string.Equals(o.Name, "a", StringComparison.OrdinalIgnoreCase)));
        Assert.Equal(Field("name").Eq("a"), _harness.Filter(o => string.Equals(o.Name, "a")));
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => string.Equals(o.Name, "a", StringComparison.Ordinal)));
    }

    [Fact]
    public void LwnrdbFunctionsTranslateToTheirOperators()
    {
        var target = new GeoPoint(1, 2);
        Assert.Equal(Distance("location", target, GeoDistanceComparator.SmallerThan, 10), _harness.Filter(o => LwnrdbFunctions.Distance(o.Location, target) < 10));
        Assert.Equal(Distance("location", target, GeoDistanceComparator.GreaterThanOrEqual, 10), _harness.Filter(o => 10 <= LwnrdbFunctions.Distance(o.Location, target)));
        Assert.Equal(Distance("location", target, GeoDistanceComparator.Equal, 10), _harness.Filter(o => LwnrdbFunctions.Distance(o.Location, target) == 10));
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => LwnrdbFunctions.Distance(o.Location, target) != 10));
        Assert.Equal(Script("s"), _harness.Filter(o => LwnrdbFunctions.Script("s")));
        Assert.Equal(And(Field("paid").Eq(true), Script("s")), _harness.Filter(o => o.Paid && LwnrdbFunctions.Script("s")));
        Assert.Throws<InvalidOperationException>(() => LwnrdbFunctions.Distance(target, target));
        Assert.Throws<InvalidOperationException>(() => LwnrdbFunctions.Within(target));
        Assert.Throws<InvalidOperationException>(() => LwnrdbFunctions.Script("s"));
    }

    [Fact]
    public void CapturedValuesAreReadOnEveryExecution()
    {
        var limit = 5.0;
        var query = _harness.Linq.Where(o => o.Total > limit);
        Assert.Equal(Field("total").Gt(5), ((FilterStep)query.ToQuery().Steps[0]).Filter);
        limit = 7;
        Assert.Equal(Field("total").Gt(7), ((FilterStep)query.ToQuery().Steps[0]).Filter);
        var holder = new Holder { Limit = 3 };
        Assert.Equal(Field("total").Gt(3), _harness.Filter(o => o.Total > holder.Limit));
        for (var index = 0; index < 2; index++)
        {
            var copy = index;
            Assert.Equal(Field("count").Eq(copy), _harness.Filter(o => o.Count == copy));
        }
        var zoned = new LinqHarness(TimeZoneInfo.Utc);
        var todayInUtc = DateTime.SpecifyKind(TimeZoneInfo.ConvertTime(DateTime.Today, TimeZoneInfo.Utc), DateTimeKind.Unspecified);
        Assert.Equal(Field("createdAt").Lt(todayInUtc), zoned.Filter(o => o.CreatedAt < DateTime.Today));
        Assert.Throws<Errors.MappingException>(() => _harness.Filter(o => o.CreatedAt < DateTime.Today));
    }

    [Fact]
    public void UntranslatableShapesAreRefusedNamingTheExpression()
    {
        var error = Assert.Throws<NotSupportedException>(() => _harness.Filter(o => o.Tags.Any(tag => tag == "x")));
        Assert.StartsWith("LWNRDB cannot translate", error.Message, StringComparison.Ordinal);
        Assert.Contains("Any", error.Message, StringComparison.Ordinal);
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => o.Total == o.Count));
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => o.Name!.StartsWith('a')));
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => true));
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => o.Name!.ToUpperInvariant() == "A"));
        var query = _harness.Linq;
        Assert.Throws<NotSupportedException>(() => _harness.Filter(o => query.Any(other => other.Name == o.Name)));
    }

    private sealed class Holder
    {
        public double Limit { get; init; }
    }
}
