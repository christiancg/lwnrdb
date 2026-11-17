using System;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;

namespace Lwnrdb.Client.Query;

public abstract record PipelineStep;

public sealed record FilterStep(Filter Filter) : PipelineStep;

public sealed record MapStep(ImmutableArray<MapOperation> Operations) : PipelineStep
{
    public bool Equals(MapStep? other) => other is not null && Operations.SequenceEqual(other.Operations);

    public override int GetHashCode() => Operations.Length;
}

public sealed record GroupByStep(string Field) : PipelineStep;

public sealed record JoinStep(string Collection, string LocalField, string RemoteField, string AsField) : PipelineStep;

public sealed record CountStep : PipelineStep;

public sealed record DistinctStep(string? Field) : PipelineStep;

public sealed record LimitStep(int Limit) : PipelineStep;

public sealed record SkipStep(int Skip) : PipelineStep;

public sealed record SortStep(string Field, bool Ascending) : PipelineStep;

public sealed record ReduceStep(string Script, bool HasInitialValue, JsonNode? InitialValue, string? ResultField) : PipelineStep
{
    public bool Equals(ReduceStep? other) =>
        other is not null
        && string.Equals(Script, other.Script, StringComparison.Ordinal)
        && HasInitialValue == other.HasInitialValue
        && JsonNode.DeepEquals(InitialValue, other.InitialValue)
        && string.Equals(ResultField, other.ResultField, StringComparison.Ordinal);

    public override int GetHashCode() => HashCode.Combine(Script, HasInitialValue, ResultField);
}
