using System.Linq;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Fixtures;

public sealed class CatalogueTests
{
    [Fact]
    public void OperationsEqualOperationsJson()
    {
        var expected = ProtocolFixtures.Read("operations.json")["operations"]!.AsArray()
            .Select(entry => $"{entry!["name"]} {entry["scope"]} {entry["adminOnly"]} {entry["allowedInTransaction"]}")
            .Order();
        var actual = Operation.All
            .Select(operation => $"{operation.Name} {WireNames.Upper(operation.Scope.ToString())} {Lower(operation.AdminOnly)} {Lower(operation.AllowedInTransaction)}")
            .Order();
        Assert.Equal(expected, actual);
    }

    [Fact]
    public void ErrorCodesEqualErrorCodesJson()
    {
        var expected = ProtocolFixtures.Read("error-codes.json")["errorCodes"]!.AsArray()
            .Select(entry => $"{entry!["code"]} {entry["name"]} {entry["status"]} {Lower(entry["retryable"]!.GetValue<bool>())} {entry["message"]?.GetValue<string>()}")
            .Order();
        var actual = ErrorCode.All
            .Select(code => $"{code.Code} {code.Name} {WireNames.Of(code.Status)} {Lower(code.IsRetryable)} {code.Message}")
            .Order();
        Assert.Equal(expected, actual);
    }

    [Fact]
    public void CustomTypesOperatorsAndComparatorsEqualCustomTypesJson()
    {
        var catalogue = ProtocolFixtures.Read("custom-types.json");
        Assert.Equal(CustomValues.DetectionRegex, catalogue["detectionRegex"]!.GetValue<string>());
        Assert.Equal(
            catalogue["geoDistanceComparators"]!.AsArray().Select(item => item!.GetValue<string>()),
            System.Enum.GetValues<GeoDistanceComparator>().Select(WireNames.Of));
        var types = catalogue["types"]!.AsArray();
        Assert.Equal(
            types.Select(type => type!["name"]!.GetValue<string>()).Order(),
            System.Enum.GetValues<CustomType>().Select(WireNames.Of).Order());
        var operators = types
            .SelectMany(type => type!["predicateOperators"]!.AsArray().Concat(type["rankingOperators"]!.AsArray()))
            .Select(item => item!.GetValue<string>())
            .Order();
        var encoder = new PipelineEncoder(null);
        var produced = new[]
            {
                Filters.Distance("f", new GeoPoint(0, 0), GeoDistanceComparator.Equal, 1),
                Filters.Within("f", new GeoPoint(0, 0), new GeoPoint(1, 0), new GeoPoint(0, 1)),
                Filters.Nearest("f", Vector.Of(1), 1),
            }
            .Select(filter => encoder.Filter(filter)["customOperatorName"]!.GetValue<string>())
            .Order();
        Assert.Equal(operators, produced);
    }

    private static string Lower(bool value) => value ? "true" : "false";
}
