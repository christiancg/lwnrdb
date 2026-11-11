using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Consumer;

public sealed class Purchase
{
    public string? Id { get; set; }

    public string? Status { get; set; }

    public double Total { get; set; }

    public DateTime PlacedAt { get; set; }

    public GeoPoint Location { get; set; }
}

public static class Program
{
    public static async Task<int> Main()
    {
        using var server = new TcpListener(IPAddress.Loopback, 0);
        server.Start();
        _ = AnswerEverythingAsync(server);
        await using var client = await LwnrdbClient.ConnectAsync(new ClientSettings
        {
            Hosts = [new ServerAddress("127.0.0.1", ((IPEndPoint)server.LocalEndpoint).Port)],
        });
        var purchases = client.Database("shop").Collection<Purchase>("purchases");
        var fluent = purchases.Query()
            .Filter(And(Field("status").Eq("active"), Field("total").Gt(100)))
            .Sort("total", Order.Desc)
            .Limit(10);
        var methodSyntax = purchases.AsQueryable()
            .Where(p => p.Status == "active" && p.Total > 100)
            .OrderByDescending(p => p.Total)
            .Take(10)
            .ToQuery();
        var querySyntax = (from p in purchases.AsQueryable()
                           where p.Status == "active" && p.Total > 100
                           orderby p.Total descending
                           select p).Take(10).ToQuery();
        Console.WriteLine(fluent);
        Console.WriteLine(methodSyntax);
        Console.WriteLine(querySyntax);
        var near = purchases.AsQueryable()
            .Where(p => LwnrdbFunctions.Distance(p.Location, new GeoPoint(40.71, -74.0)) < 1000)
            .ToQuery();
        Console.WriteLine(near);
        Console.WriteLine((await purchases.AsQueryable().Where(p => p.PlacedAt < new DateTime(2024, 1, 1)).ToListAsync()).Count);
        return fluent.Equals(methodSyntax) && fluent.Equals(querySyntax) ? 0 : 1;
    }

    private static async Task AnswerEverythingAsync(TcpListener server)
    {
        while (true)
        {
            var connection = await server.AcceptTcpClientAsync();
            _ = Task.Run(async () =>
            {
                using var reader = new StreamReader(connection.GetStream());
                await using var writer = new StreamWriter(connection.GetStream()) { AutoFlush = true, NewLine = "\n" };
                while (await reader.ReadLineAsync() is { } line)
                {
                    var request = JsonNode.Parse(line)!;
                    await writer.WriteLineAsync(new JsonObject { ["type"] = request["type"]?.DeepClone(), ["status"] = "OK" }.ToJsonString());
                }
            });
        }
    }
}
