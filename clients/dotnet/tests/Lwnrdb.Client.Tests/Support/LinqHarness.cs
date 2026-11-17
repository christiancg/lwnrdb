using System;
using System.Linq;
using System.Linq.Expressions;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Tests.Support;

internal sealed class LinqHarness
{
    public LinqHarness(TimeZoneInfo? zone = null, JsonSerializerOptions? json = null)
    {
        Context = RecordingChannel.Context(Channel, zone, json);
        var shop = new Database(Context, "shop");
        Orders = shop.Collection<Order>("orders");
        Customers = shop.Collection<Customer>("customers");
        Fluent = Orders.Query();
    }

    public RecordingChannel Channel { get; } = new();

    public Internal.ClientContext Context { get; }

    public Collection<Order> Orders { get; }

    public Collection<Customer> Customers { get; }

    public Query<Order> Fluent { get; }

    public IQueryable<Order> Linq => Orders.AsQueryable();

    public Filter Filter(Expression<Func<Order, bool>> predicate) =>
        ((FilterStep)Linq.Where(predicate).ToQuery().Steps.Single()).Filter;

    public JsonNode? Value(Expression<Func<Order, bool>> predicate) => ((FieldFilter)Filter(predicate)).Value;

    public void Reply(params JsonObject[] rows) =>
        Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject { ["results"] = new JsonArray([.. rows.Select(row => row.DeepClone())]) });
}
