using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.IntegrationTests.Support;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Values;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.IntegrationTests;

public sealed class CustomTypesIT(CustomTypesIT.Server fixture) : IClassFixture<CustomTypesIT.Server>
{
    private static readonly GeoPoint NewYork = new(40.71, -74.0);
    private static readonly GeoPoint Lima = new(-12.05, -77.04);

    private Collection<Place> Places => fixture.Client.Database("types_it").Collection<Place>("places");

    private static List<string> Ids(IEnumerable<Place> rows) => [.. rows.Select(row => row.Id!).Order(StringComparer.Ordinal)];

    [Fact]
    public async Task EveryCustomTypeRoundTrips()
    {
        var ny = await Places.FindByIdAsync("ny");
        Assert.Equal(new DateTime(2024, 1, 1, 10, 0, 0, 123), ny!.OpenedAt);
        Assert.Equal(DateTimeKind.Unspecified, ny.OpenedAt.Kind);
        Assert.Equal(new TimeOnly(9, 30), ny.OpensAt);
        Assert.Equal(NewYork, ny.Location);
        Assert.Equal(Vector.Of(1, 0, 0), ny.Embedding);
        Assert.Equal([0.5f, 0.25f], ny.Floats!);
        var raw = await fixture.Client.Database("types_it").Collection("places").FindByIdAsync("ny");
        Assert.Equal(NewYork, raw!.GetGeoPoint("location"));
        Assert.Equal(new TimeOnly(9, 30), raw.GetTimeOnly("opensAt"));
        Assert.Equal(Vector.Of(0.5, 0.25), raw.GetVector("floats"));
    }

    [Fact]
    public async Task DateTimesAndTimesCompareAndSortChronologically()
    {
        Assert.Equal(["ny"], Ids(await Places.Query().Filter(Field("openedAt").Lt(new DateTime(2024, 6, 1))).ToListAsync()));
        Assert.Equal(["lima", "rome"], Ids(await Places.AsQueryable().Where(p => p.OpenedAt >= new DateTime(2024, 6, 1)).ToListAsync()));
        Assert.Equal(["rome"], Ids(await Places.AsQueryable().Where(p => p.OpensAt > new TimeOnly(10, 0)).ToListAsync()));
        var sorted = await Places.AsQueryable().OrderByDescending(p => p.OpenedAt).Select(p => p.Id).ToListAsync();
        Assert.Equal(["rome", "lima", "ny"], sorted);
    }

    [Fact]
    public async Task GeoDistanceWithinAndIn()
    {
        Assert.Equal(["ny"], Ids(await Places.Query().Filter(Distance("location", NewYork, GeoDistanceComparator.SmallerThanOrEqual, 1000)).ToListAsync()));
        Assert.Equal(2, await Places.AsQueryable().CountAsync(p => LwnrdbFunctions.Distance(p.Location, NewYork) > 1000));
        var square = Within("location", new GeoPoint(40, -75), new GeoPoint(41, -75), new GeoPoint(41, -73), new GeoPoint(40, -73));
        Assert.Equal(["ny"], Ids(await Places.Query().Filter(square).ToListAsync()));
        Assert.Equal(["lima", "ny"], Ids(await Places.Query().Filter(Field("location").In(NewYork, Lima)).ToListAsync()));
    }

    [Fact]
    public async Task NearestApproximateAndExact()
    {
        var approximate = await Places.Query().Filter(Nearest("embedding", Vector.Of(1, 0.1, 0), 1)).ToListAsync();
        Assert.Equal(["ny"], Ids(approximate));
        var exact = await Places.AsQueryable().Nearest(p => p.Embedding, Vector.Of(0, 1, 0.1), 2, exact: true).ToListAsync();
        Assert.Equal(["lima", "rome"], Ids(exact));
        Assert.Equal(["ny"], Ids(await Places.AsQueryable().Where(p => p.Embedding == Vector.Of(1, 0, 0)).ToListAsync()));
    }

    [Fact]
    public async Task AVectorPastTheDimensionLimitFailsOnlyItsRequest()
    {
        var error = await Assert.ThrowsAsync<LwnrdbException>(() =>
            Places.SaveAsync(new Place { Id = "big", Embedding = Vector.Of([.. Enumerable.Repeat(1.0, 9)]) }));
        Assert.StartsWith("400", error.RawCode, StringComparison.Ordinal);
        Assert.NotNull(await Places.FindByIdAsync("ny"));
    }

    public sealed class Server : ServerFixture
    {
        protected override IReadOnlyDictionary<string, string> Config => new Dictionary<string, string> { ["maxVectorDimensions"] = "8" };

        protected override async Task SeedAsync()
        {
            var database = Client.Database("types_it");
            await database.CreateAsync();
            var places = database.Collection<Place>("places");
            await places.CreateAsync();
            await places.SaveAllAsync([
                new Place { Id = "ny", OpenedAt = new DateTime(2024, 1, 1, 10, 0, 0, 123), OpensAt = new TimeOnly(9, 30), Location = NewYork, Embedding = Vector.Of(1, 0, 0), Floats = [0.5f, 0.25f] },
                new Place { Id = "lima", OpenedAt = new DateTime(2024, 6, 1), OpensAt = new TimeOnly(8, 15), Location = Lima, Embedding = Vector.Of(0, 1, 0) },
                new Place { Id = "rome", OpenedAt = new DateTime(2025, 2, 1, 7, 0, 0), OpensAt = new TimeOnly(11, 0, 30), Location = new GeoPoint(41.9, 12.5), Embedding = Vector.Of(0, 0.9, 0.2) },
            ]);
        }
    }
}
