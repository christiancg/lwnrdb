using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;

namespace Lwnrdb.Client.Tests.Support;

public sealed record FixtureCase(string Name, JsonObject Body);

public static class ProtocolFixtures
{
    public static string ClientsRoot { get; } = FindClientsRoot();

    public static string ProtocolRoot => Path.Combine(ClientsRoot, "protocol");

    public static JsonObject Read(string relativePath) =>
        (JsonObject)JsonNode.Parse(File.ReadAllText(Path.Combine(ProtocolRoot, relativePath)))!;

    public static IReadOnlyList<FixtureCase> Requests() => Cases("requests");

    public static IReadOnlyList<FixtureCase> Responses() => Cases("responses");

    public static FixtureCase Request(string name) => Requests().Single(fixture => fixture.Name == name);

    private static List<FixtureCase> Cases(string folder) =>
        [.. Directory.GetFiles(Path.Combine(ProtocolRoot, "fixtures", folder), "*.json")
            .Order(StringComparer.Ordinal)
            .SelectMany(file => Read(Path.Combine("fixtures", folder, Path.GetFileName(file)))["cases"]!.AsArray())
            .Select(body => new FixtureCase(body!["name"]!.GetValue<string>(), body.AsObject()))];

    private static string FindClientsRoot()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            if (Directory.Exists(Path.Combine(directory.FullName, "protocol", "fixtures")))
            {
                return directory.FullName;
            }
        }
        throw new InvalidOperationException("clients/protocol was not found above the test binaries");
    }
}
