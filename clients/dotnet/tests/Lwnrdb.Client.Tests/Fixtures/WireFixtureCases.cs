using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using static Lwnrdb.Client.Query.Filters;
using Order = Lwnrdb.Client.Query.Order;

namespace Lwnrdb.Client.Tests.Fixtures;

internal delegate Task FixtureCall(Recorder recorder);

internal static class WireFixtureCases
{
    public const string ReduceSum = "export default (acc, doc) => acc + doc.total;";

    public static readonly (string Name, Func<FieldCondition, object?, Filter> Compare)[] Comparisons =
    [
        ("equals", (condition, value) => condition.Eq(value)),
        ("not_equals", (condition, value) => condition.Ne(value)),
        ("greater_than", (condition, value) => condition.Gt(value)),
        ("greater_than_equals", (condition, value) => condition.Gte(value)),
        ("smaller_than", (condition, value) => condition.Lt(value)),
        ("smaller_than_equals", (condition, value) => condition.Lte(value)),
    ];

    private static readonly Filter Active = Field("status").Eq("active");

    public static Dictionary<string, FixtureCall> All()
    {
        var cases = new Dictionary<string, FixtureCall>(StringComparer.Ordinal);
        Data(cases);
        Aggregate(cases);
        MapCases(cases);
        CustomTypes(cases);
        WireFixtureAdminCases.Add(cases);
        return cases;
    }

    private static FixtureCall Filtered(Filter filter) => r => r.Orders.Query().Filter(filter).ToListAsync();

    private static void Data(Dictionary<string, FixtureCall> cases)
    {
        cases["data.save_with_id"] = r => r.Orders.SaveAsync(Document.Of("_id", "o-1", "status", "active", "total", 120));
        cases["data.save_without_id"] = r => r.Orders.SaveAsync(Document.Of("status", "new"));
        cases["data.save_nested"] = r => r.Orders.SaveAsync(Document.Of(
            "_id", "o-2", "customer", Document.Of("name", "Ana", "tags", new object?[] { "vip", null, true, 1.5 })));
        cases["data.bulk_save"] = r => r.Orders.SaveAllAsync([Document.Of("_id", "o-1", "total", 1), Document.Of("total", 2)]);
        cases["data.find_by_id"] = r => r.Orders.FindByIdAsync("o-1");
        cases["data.find_by_id_dirty"] = r => r.Orders.FindByIdAsync("o-1", dirtyRead: true);
        cases["data.delete"] = r => r.Orders.DeleteAsync("o-1");
        cases["data.delete_where"] = r => r.Orders.DeleteWhereAsync(And(Field("status").Eq("archived"), Field("total").Lt(100)));
    }

    private static void Aggregate(Dictionary<string, FixtureCall> cases)
    {
        cases["aggregate.all"] = r => r.Orders.Query().ToListAsync();
        cases["aggregate.filter_sort_limit"] = r => r.Orders.Query().Filter(Active).Sort("createdAt", Order.Desc).Limit(10).ToListAsync();
        cases["aggregate.skip_limit"] = r => r.Orders.Query().Skip(20).Limit(10).ToListAsync();
        cases["aggregate.sort_ascending"] = r => r.Orders.Query().Sort("name", Order.Asc).ToListAsync();
        cases["aggregate.count"] = r => r.Orders.Query().Filter(Active).CountAsync();
        cases["aggregate.group_by"] = r => r.Orders.Query().GroupBy("status").ToListAsync();
        cases["aggregate.distinct_field"] = r => r.Orders.Query().Distinct("status").ToListAsync();
        cases["aggregate.distinct_documents"] = r => r.Orders.Query().Distinct().ToListAsync();
        cases["aggregate.join"] = r => r.Orders.Query().Join("customers", "customerId", "_id", "customer").ToListAsync();
        cases["aggregate.reduce"] = r => r.Orders.Query().Reduce(ReduceSum, 0, "sum").ToListAsync();
        cases["aggregate.reduce_defaults"] = r => r.Orders.Query().Reduce("export default (acc, doc) => acc;").ToListAsync();
        cases["aggregate.dirty_read"] = r => r.Orders.Query().Filter(Active).DirtyRead().ToListAsync();
        cases["aggregate.analyze"] = r => r.Orders.Query().Filter(Active).AnalyzeAsync();
        cases["aggregate.filter_in"] = Filtered(Field("status").In("active", "new"));
        cases["aggregate.filter_not_in"] = Filtered(Field("status").NotIn("closed"));
        cases["aggregate.filter_contains"] = Filtered(Field("name").Contains("an"));
        cases["aggregate.filter_null"] = Filtered(Field("deletedAt").Eq(null));
        cases["aggregate.filter_object_value"] = Filtered(Field("address").Eq(Document.Of("city", "Lima")));
        cases["aggregate.filter_script"] = Filtered(Script("export default (doc) => doc.total > 100;"));
        foreach (var (name, compare) in Comparisons)
        {
            cases[$"aggregate.filter_{name}"] = Filtered(compare(Field("total"), 100));
        }
        (string Name, Func<Filter[], Filter> Combine)[] conjunctions =
            [("and", And), ("or", Or), ("nor", Nor), ("xor", Xor), ("nand", Nand)];
        foreach (var (name, combine) in conjunctions)
        {
            cases[$"aggregate.conjunction_{name}"] = Filtered(combine([Active, Field("total").Gt(100)]));
        }
        cases["aggregate.conjunction_nested"] =
            Filtered(And(Active, Or(Field("total").Lt(10), Script("export default (doc) => doc.vip;"))));
    }

    private static void MapCases(Dictionary<string, FixtureCall> cases)
    {
        (string Name, Func<object?[], MidOperation> Operator)[] arithmetic =
        [
            ("avg", MidOps.Avg), ("sum", MidOps.Sum), ("subs", MidOps.Subs), ("max", MidOps.Max), ("min", MidOps.Min),
            ("multiply", MidOps.Multiply), ("divide", MidOps.Divide), ("pow", MidOps.Pow), ("root", MidOps.Root),
        ];
        foreach (var (name, op) in arithmetic)
        {
            cases[$"aggregate.map_{name}"] = r => r.Orders.Query().Map(MapOps.Set("result", op(["price", "qty", 2]))).ToListAsync();
        }
        cases["aggregate.map_concat"] = r => r.Orders.Query()
            .Map(MapOps.Set("label", MidOps.Concat("name", MidOps.Literal(" / "), "status"))).ToListAsync();
        cases["aggregate.map_abs"] = r => r.Orders.Query().Map(MapOps.Set("delta", MidOps.Abs("change"))).ToListAsync();
        cases["aggregate.map_size"] = r => r.Orders.Query().Map(MapOps.Set("count", MidOps.Size("items"))).ToListAsync();
        cases["aggregate.map_script"] = r => r.Orders.Query()
            .Map(MapOps.Set("total", MidOps.Script("export default (doc) => doc.price * doc.qty;"))).ToListAsync();
        cases["aggregate.map_remove"] = r => r.Orders.Query().Map(MapOps.Remove("secret")).ToListAsync();
        cases["aggregate.map_conditional"] = r => r.Orders.Query()
            .Map(
                MapOps.Set("flag", MidOps.Sum(1)).When(Active),
                MapOps.Remove("draft").When(Field("status").Eq("closed")))
            .ToListAsync();
        foreach (var type in new[] { CastType.Number, CastType.String, CastType.Boolean })
        {
            cases[$"aggregate.map_cast_{type.ToString().ToLowerInvariant()}"] =
                r => r.Orders.Query().Map(MapOps.Set("converted", MidOps.Cast("raw", type))).ToListAsync();
        }
    }

    private static void CustomTypes(Dictionary<string, FixtureCall> cases)
    {
        var newYork = new GeoPoint(40.71, -74.0);
        var embedding = Vector.Of(0.12, 0.44, 0.91);
        cases["custom_types.save_all_types"] = r => r.Orders.SaveAsync(Document.Of(
            "_id", "p-1",
            "openedAt", new DateTime(2024, 1, 1, 10, 0, 0),
            "opensAt", new TimeOnly(9, 30),
            "location", newYork,
            "embedding", embedding,
            "stops", new[] { new GeoPoint(1.0, 2.0), new GeoPoint(3.0, 4.0) }));
        cases["custom_types.filter_in_geo"] = Filtered(Field("location").In(new GeoPoint(1, 2), new GeoPoint(3, 4)));
        cases["custom_types.within_triangle"] = Filtered(Within(
            "location", new GeoPoint(40.0, -74.2), new GeoPoint(40.9, -74.2), new GeoPoint(40.9, -73.7)));
        cases["custom_types.within_many"] = Filtered(Within(
            "location",
            new GeoPoint(40.0, -74.2),
            new GeoPoint(40.9, -74.2),
            new GeoPoint(40.9, -73.7),
            new GeoPoint(40.0, -73.7),
            new GeoPoint(39.5, -74.0)));
        cases["custom_types.nearest"] = Filtered(Nearest("embedding", embedding, 10));
        cases["custom_types.nearest_exact"] = Filtered(Nearest("embedding", embedding, 3).Exact());
        cases["custom_types.sort_datetime"] = r => r.Orders.Query().Sort("openedAt", Order.Desc).ToListAsync();
        foreach (var (name, compare) in Comparisons)
        {
            cases[$"custom_types.{name}_datetime"] = Filtered(compare(Field("openedAt"), new DateTime(2024, 1, 1, 10, 0, 30)));
            cases[$"custom_types.{name}_time"] = Filtered(compare(Field("opensAt"), new TimeOnly(9, 30, 15)));
        }
        cases["custom_types.equals_geo"] = Filtered(Field("location").Eq(newYork));
        cases["custom_types.equals_vector"] = Filtered(Field("embedding").Eq(Vector.Of(1.0, 0.0)));
        foreach (var comparator in Enum.GetValues<GeoDistanceComparator>())
        {
            cases[$"custom_types.distance_{Internal.WireNames.Of(comparator).ToLowerInvariant()}"] =
                Filtered(Distance("location", newYork, comparator, 1000));
        }
        foreach (var type in Enum.GetValues<CustomType>())
        {
            cases[$"custom_types.cast_{Internal.WireNames.Of(type)}"] =
                r => r.Orders.Query().Map(MapOps.Set("typed", MidOps.Cast("raw", type))).ToListAsync();
        }
    }
}
