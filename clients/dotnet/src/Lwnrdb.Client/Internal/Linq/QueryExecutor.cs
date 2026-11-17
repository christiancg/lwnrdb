using System;
using System.Collections.Generic;
using System.Linq.Expressions;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Linq;

namespace Lwnrdb.Client.Internal.Linq;

internal static class QueryExecutor
{
    public static QueryModel Translate(LwnrdbQueryProvider provider, Expression expression) =>
        new QueryModelBuilder(provider).Build(PartialEvaluator.Evaluate(expression));

    public static async Task<object?> ExecuteAsync(LwnrdbQueryProvider provider, Expression expression, Type resultType, CancellationToken ct)
    {
        var model = Translate(provider, expression);
        IReadOnlyList<Document> rows = model.SendsNothing
            ? []
            : await provider.Root.AggregateAsync(model.Steps, model.DirtyRead, ct).ConfigureAwait(false);
        return ResultShaper.Shape(model, rows, resultType, provider.Root.Context.Json);
    }
}
