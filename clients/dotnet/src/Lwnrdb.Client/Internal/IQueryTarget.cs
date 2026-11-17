using System.Collections.Generic;
using System.Collections.Immutable;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal;

internal interface IQueryTarget<T>
{
    string Database { get; }

    string Collection { get; }

    ClientContext Context { get; }

    T DecodeRow(Document row);

    Task<IReadOnlyList<Document>> AggregateAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct);

    Task<AnalyzeResult> AnalyzeAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct);
}
