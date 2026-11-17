using System;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal;

internal sealed class PipelineEncoder(TimeZoneInfo? temporalZone)
{
    private const string CustomOperator = "customOperatorName";
    private const string LiteralPrefix = "-";

    public JsonArray Steps(ImmutableArray<PipelineStep> steps) => [.. steps.Select(Step)];

    public JsonObject Filter(Filter filter) => filter switch
    {
        FieldFilter field => new JsonObject
        {
            ["fieldOperatorType"] = WireNames.Of(field.Operator),
            ["field"] = field.Field,
            ["value"] = field.Value?.DeepClone(),
        },
        ConjunctionFilter conjunction => new JsonObject
        {
            ["conjunctionType"] = WireNames.Upper(conjunction.Conjunction.ToString()),
            ["operators"] = new JsonArray([.. conjunction.Operands.Select(Filter)]),
        },
        DistanceFilter distance => new JsonObject
        {
            [CustomOperator] = "distance",
            ["field"] = distance.Field,
            ["value"] = CustomValues.Format(distance.Target),
            ["comparator"] = WireNames.Of(distance.Comparator),
            ["distance"] = distance.Metres,
        },
        WithinFilter within => new JsonObject
        {
            [CustomOperator] = "within",
            ["field"] = within.Field,
            ["polygon"] = new JsonArray([.. within.Polygon.Select(point => (JsonNode)CustomValues.Format(point))]),
        },
        NearestFilter nearest => Nearest(nearest),
        ScriptFilter script => new JsonObject { ["script"] = script.Source },
        _ => throw new ArgumentException($"unknown filter {filter.GetType().Name}", nameof(filter)),
    };

    private static JsonObject Nearest(NearestFilter nearest)
    {
        var encoded = new JsonObject
        {
            [CustomOperator] = "nearest",
            ["field"] = nearest.Field,
            ["value"] = CustomValues.Format(nearest.Query),
            ["k"] = nearest.K,
        };
        if (nearest.IsExact)
        {
            encoded["exact"] = true;
        }
        return encoded;
    }

    private JsonObject Step(PipelineStep step) => step switch
    {
        FilterStep filter => Typed("FILTER", ("operator", Filter(filter.Filter))),
        MapStep map => Typed("MAP", ("operators", new JsonArray([.. map.Operations.Select(MapOperation)]))),
        GroupByStep groupBy => Typed("GROUP_BY", ("fieldName", groupBy.Field)),
        JoinStep join => Typed(
            "JOIN",
            ("joinCollection", join.Collection),
            ("localField", join.LocalField),
            ("remoteField", join.RemoteField),
            ("asField", join.AsField)),
        CountStep => Typed("COUNT"),
        DistinctStep { Field: null } => Typed("DISTINCT"),
        DistinctStep distinct => Typed("DISTINCT", ("fieldName", distinct.Field)),
        LimitStep limit => Typed("LIMIT", ("limit", limit.Limit)),
        SkipStep skip => Typed("SKIP", ("skip", skip.Skip)),
        SortStep sort => Typed("SORT", ("fieldName", sort.Field), ("ascending", sort.Ascending)),
        ReduceStep reduce => Reduce(reduce),
        _ => throw new ArgumentException($"unknown step {step.GetType().Name}", nameof(step)),
    };

    private static JsonObject Reduce(ReduceStep reduce)
    {
        var encoded = Typed("REDUCE", ("script", reduce.Script));
        if (reduce.HasInitialValue)
        {
            encoded["initialValue"] = reduce.InitialValue?.DeepClone();
        }
        if (reduce.ResultField is not null)
        {
            encoded["resultField"] = reduce.ResultField;
        }
        return encoded;
    }

    private JsonObject MapOperation(MapOperation operation)
    {
        var encoded = new JsonObject { ["fieldName"] = operation.Field };
        if (operation.Operation is not null)
        {
            encoded["operator"] = MidOperation(operation.Operation);
        }
        if (operation.Condition is not null)
        {
            encoded["condition"] = Filter(operation.Condition);
        }
        return encoded;
    }

    private JsonObject MidOperation(MidOperation operation) => operation switch
    {
        ArrayOperation array => new JsonObject
        {
            ["type"] = WireNames.Upper(array.Operator.ToString()),
            ["operands"] = new JsonArray([.. array.Operands.Select(Operand)]),
        },
        FieldFunctionOperation function => new JsonObject
        {
            ["type"] = WireNames.Upper(function.Function.ToString()),
            ["operand"] = function.Field,
        },
        CastOperation cast => Cast(cast),
        ScriptOperation script => new JsonObject { ["type"] = "SCRIPT", ["script"] = script.Source },
        _ => throw new ArgumentException($"unknown operation {operation.GetType().Name}", nameof(operation)),
    };

    private static JsonObject Cast(CastOperation cast)
    {
        var encoded = new JsonObject
        {
            ["type"] = "CAST",
            ["fieldName"] = cast.Field,
            ["toType"] = WireNames.Upper(cast.Type.ToString()),
        };
        if (cast.CustomType is { } customType)
        {
            encoded["customTypeName"] = WireNames.Of(customType);
        }
        return encoded;
    }

    private JsonNode? Operand(object? operand) => operand switch
    {
        Literal literal => LiteralPrefix + literal.Text,
        string field => field,
        _ => DocumentValues.Encode(operand, string.Empty, temporalZone),
    };

    private static JsonObject Typed(string type, params (string Field, JsonNode? Value)[] fields)
    {
        var encoded = new JsonObject { ["type"] = type };
        foreach (var (field, value) in fields)
        {
            encoded[field] = value;
        }
        return encoded;
    }
}
