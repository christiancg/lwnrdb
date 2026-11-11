using System;
using System.Linq;
using System.Text.Json;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class MemberPathsTests
{
    private readonly LinqHarness _harness = new();

    [Fact]
    public void MembersAreCamelCasedByDefault() =>
        Assert.Equal(Field("customerId").Eq("c"), _harness.Filter(o => o.CustomerId == "c"));

    [Fact]
    public void JsonPropertyNameWins() =>
        Assert.Equal(Field("placed_at").Lt(new DateTime(2024, 1, 1)), _harness.Filter(o => o.PlacedAt < new DateTime(2024, 1, 1)));

    [Fact]
    public void ACustomNamingPolicyFromTheSettingsApplies()
    {
        var snake = new LinqHarness(json: new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower });
        Assert.Equal(Field("customer_id").Eq("c"), snake.Filter(o => o.CustomerId == "c"));
        Assert.Equal(Field("_id").Eq("o-1"), snake.Filter(o => o.Id == "o-1"));
    }

    [Fact]
    public void IdIsTheIdField() => Assert.Equal(Field("_id").Eq("o-1"), _harness.Filter(o => o.Id == "o-1"));

    [Fact]
    public void NestedChainsBecomeDottedPaths() =>
        Assert.Equal(Field("address.city").Eq("Lima"), _harness.Filter(o => o.Address!.City == "Lima"));

    [Fact]
    public void AnIgnoredMemberIsRefused()
    {
        var error = Assert.Throws<NotSupportedException>(() => _harness.Filter(o => o.Secret == "x"));
        Assert.Contains("Secret", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ConversionsAndNullableValueResolveToTheMember()
    {
        Assert.Equal(Field("count").Gt(2.5), _harness.Filter(o => (double)o.Count > 2.5));
        Assert.Equal(Field("discount").Gt(1), _harness.Filter(o => o.Discount!.Value > 1));
    }

    [Fact]
    public void RecordsWithAPrimaryConstructorResolveTheirProperties()
    {
        var points = new Database(_harness.Context, "shop").Collection<Point>("points");
        var query = points.AsQueryable().Where(p => p.Label == "a" && p.Id == "p").ToQuery();
        Assert.Equal(And(Field("label").Eq("a"), Field("_id").Eq("p")), ((FilterStep)query.Steps[0]).Filter);
    }

    [Fact]
    public void ATypeWhoseIdIsNotAStringIsRefusedWhenTheCollectionIsCreated()
    {
        var shop = new Database(_harness.Context, "shop");
        Assert.Throws<ArgumentException>(() => shop.Collection<NumericId>("numbers"));
    }
}
