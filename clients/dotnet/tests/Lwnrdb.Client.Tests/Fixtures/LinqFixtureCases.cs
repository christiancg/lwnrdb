using System;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Tests.Fixtures;

internal static class LinqFixtureCases
{
    private const string Vip = "export default (doc) => doc.vip;";

    private static readonly (string Name, Func<Expression<Func<Order, double>>, double, Expression<Func<Order, bool>>> Compare)[] TotalComparisons =
    [
        ("equals", (_, value) => o => o.Total == value),
        ("not_equals", (_, value) => o => o.Total != value),
        ("greater_than", (_, value) => o => o.Total > value),
        ("greater_than_equals", (_, value) => o => o.Total >= value),
        ("smaller_than", (_, value) => o => o.Total < value),
        ("smaller_than_equals", (_, value) => o => o.Total <= value),
    ];

    public static Dictionary<string, FixtureCall> All()
    {
        var cases = new Dictionary<string, FixtureCall>(StringComparer.Ordinal);
        Aggregate(cases);
        Conjunctions(cases);
        CustomTypes(cases);
        Temporal(cases);
        Listen(cases);
        return cases;
    }

    private static FixtureCall Filtered(Expression<Func<Order, bool>> predicate) =>
        r => r.TypedOrders.AsQueryable().Where(predicate).ToListAsync();

    private static void Aggregate(Dictionary<string, FixtureCall> cases)
    {
        var statuses = new[] { "active", "new" };
        var closed = new List<string> { "closed" };
        var lima = new Address { City = "Lima" };
        cases["aggregate.all"] = r => r.TypedOrders.AsQueryable().ToListAsync();
        cases["aggregate.filter_sort_limit"] = r => r.TypedOrders.AsQueryable()
            .Where(o => o.Status == "active").OrderByDescending(o => o.CreatedAt).Take(10).ToListAsync();
        cases["aggregate.skip_limit"] = r => r.TypedOrders.AsQueryable().Skip(20).Take(10).ToListAsync();
        cases["aggregate.sort_ascending"] = r => r.TypedOrders.AsQueryable().OrderBy(o => o.Name).ToListAsync();
        cases["aggregate.count"] = r => r.TypedOrders.AsQueryable().CountAsync(o => o.Status == "active");
        cases["aggregate.group_by"] = r => r.TypedOrders.AsQueryable().GroupBy(o => o.Status).ToListAsync();
        cases["aggregate.distinct_field"] = r => r.TypedOrders.AsQueryable().Select(o => o.Status).Distinct().ToListAsync();
        cases["aggregate.distinct_documents"] = r => r.TypedOrders.AsQueryable().Distinct().ToListAsync();
        cases["aggregate.join"] = r => r.TypedOrders.AsQueryable()
            .GroupJoin(r.Customers.AsQueryable(), o => o.CustomerId, c => c.Id, (o, customer) => new { o, customer })
            .ToListAsync();
        cases["aggregate.dirty_read"] = r => r.TypedOrders.AsQueryable().Where(o => o.Status == "active").DirtyRead().ToListAsync();
        cases["aggregate.analyze"] = r => r.TypedOrders.AsQueryable().Where(o => o.Status == "active").AnalyzeAsync();
        cases["aggregate.filter_in"] = Filtered(o => statuses.Contains(o.Status));
        cases["aggregate.filter_not_in"] = Filtered(o => !closed.Contains(o.Status!));
        cases["aggregate.filter_contains"] = Filtered(o => o.Name!.Contains("an"));
        cases["aggregate.filter_null"] = Filtered(o => o.DeletedAt == null);
        cases["aggregate.filter_object_value"] = Filtered(o => o.Address == lima);
        cases["aggregate.filter_script"] = Filtered(o => LwnrdbFunctions.Script("export default (doc) => doc.total > 100;"));
        foreach (var (name, compare) in TotalComparisons)
        {
            cases[$"aggregate.filter_{name}"] = Filtered(compare(o => o.Total, 100));
        }
    }

    private static void Conjunctions(Dictionary<string, FixtureCall> cases)
    {
        cases["aggregate.conjunction_and"] = Filtered(o => o.Status == "active" && o.Total > 100);
        cases["aggregate.conjunction_or"] = Filtered(o => o.Status == "active" || o.Total > 100);
        cases["aggregate.conjunction_nor"] = Filtered(o => !(o.Status == "active" || o.Total > 100));
        cases["aggregate.conjunction_xor"] = Filtered(o => o.Status == "active" ^ o.Total > 100);
        cases["aggregate.conjunction_nand"] = Filtered(o => !(o.Status == "active" && o.Total > 100));
        cases["aggregate.conjunction_nested"] = Filtered(o => o.Status == "active" && (o.Total < 10 || LwnrdbFunctions.Script(Vip)));
    }

    private static void CustomTypes(Dictionary<string, FixtureCall> cases)
    {
        var newYork = new GeoPoint(40.71, -74.0);
        var embedding = Vector.Of(0.12, 0.44, 0.91);
        var points = new[] { new GeoPoint(1, 2), new GeoPoint(3, 4) };
        cases["custom_types.filter_in_geo"] = Filtered(o => points.Contains(o.Location));
        cases["custom_types.within_triangle"] = Filtered(o => LwnrdbFunctions.Within(
            o.Location, new GeoPoint(40.0, -74.2), new GeoPoint(40.9, -74.2), new GeoPoint(40.9, -73.7)));
        cases["custom_types.within_many"] = Filtered(o => LwnrdbFunctions.Within(
            o.Location,
            new GeoPoint(40.0, -74.2),
            new GeoPoint(40.9, -74.2),
            new GeoPoint(40.9, -73.7),
            new GeoPoint(40.0, -73.7),
            new GeoPoint(39.5, -74.0)));
        cases["custom_types.nearest"] = r => r.TypedOrders.AsQueryable().Nearest(o => o.Embedding, embedding, 10).ToListAsync();
        cases["custom_types.nearest_exact"] = r => r.TypedOrders.AsQueryable().Nearest(o => o.Embedding, embedding, 3, exact: true).ToListAsync();
        cases["custom_types.equals_geo"] = Filtered(o => o.Location == newYork);
        cases["custom_types.equals_vector"] = Filtered(o => o.Embedding == Vector.Of(1.0, 0.0));
        cases["custom_types.distance_equals"] = Filtered(o => LwnrdbFunctions.Distance(o.Location, newYork) == 1000);
        cases["custom_types.distance_greater_than"] = Filtered(o => LwnrdbFunctions.Distance(o.Location, newYork) > 1000);
        cases["custom_types.distance_greater_than_equals"] = Filtered(o => LwnrdbFunctions.Distance(o.Location, newYork) >= 1000);
        cases["custom_types.distance_smaller_than"] = Filtered(o => 1000 > LwnrdbFunctions.Distance(o.Location, newYork));
        cases["custom_types.distance_smaller_than_equals"] = Filtered(o => LwnrdbFunctions.Distance(o.Location, newYork) <= 1000);
    }

    private static void Temporal(Dictionary<string, FixtureCall> cases)
    {
        var at = new DateTime(2024, 1, 1, 10, 0, 30);
        var time = new TimeOnly(9, 30, 15);
        cases["custom_types.sort_datetime"] = r => r.TypedOrders.AsQueryable().OrderByDescending(o => o.OpenedAt).ToListAsync();
        cases["custom_types.equals_datetime"] = Filtered(o => o.OpenedAt == at);
        cases["custom_types.not_equals_datetime"] = Filtered(o => o.OpenedAt != at);
        cases["custom_types.greater_than_datetime"] = Filtered(o => o.OpenedAt > at);
        cases["custom_types.greater_than_equals_datetime"] = Filtered(o => at <= o.OpenedAt);
        cases["custom_types.smaller_than_datetime"] = Filtered(o => o.OpenedAt < at);
        cases["custom_types.smaller_than_equals_datetime"] = Filtered(o => o.OpenedAt <= at);
        cases["custom_types.equals_time"] = Filtered(o => o.OpensAt == time);
        cases["custom_types.not_equals_time"] = Filtered(o => o.OpensAt != time);
        cases["custom_types.greater_than_time"] = Filtered(o => o.OpensAt > time);
        cases["custom_types.greater_than_equals_time"] = Filtered(o => o.OpensAt >= time);
        cases["custom_types.smaller_than_time"] = Filtered(o => time > o.OpensAt);
        cases["custom_types.smaller_than_equals_time"] = Filtered(o => o.OpensAt <= time);
    }

    private static void Listen(Dictionary<string, FixtureCall> cases)
    {
        cases["listen.filter"] = r => r.Raw(r.Encoder.Listen(
            "shop", "orders", r.TypedOrders.AsQueryable().Where(o => o.Status == "pending").ToQuery().Steps));
        cases["listen.all"] = r => r.Raw(r.Encoder.Listen("shop", "orders", r.TypedOrders.AsQueryable().ToQuery().Steps));
    }
}
