using System;
using System.Linq;
using System.Linq.Expressions;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal.Linq;

namespace Lwnrdb.Client.Linq;

public sealed class LwnrdbQueryProvider : IQueryProvider
{
    internal LwnrdbQueryProvider(IQueryRoot root)
    {
        Root = root;
    }

    internal IQueryRoot Root { get; }

    public IQueryable CreateQuery(Expression expression)
    {
        ArgumentNullException.ThrowIfNull(expression);
        var elementType = QueryableTypes.ElementTypeOf(expression.Type);
        return (IQueryable)Activator.CreateInstance(
            typeof(LwnrdbQueryable<>).MakeGenericType(elementType),
            BindingFlags.Instance | BindingFlags.NonPublic,
            null,
            [this, expression],
            null)!;
    }

    public IQueryable<TElement> CreateQuery<TElement>(Expression expression)
    {
        ArgumentNullException.ThrowIfNull(expression);
        return new LwnrdbQueryable<TElement>(this, expression);
    }

    public object? Execute(Expression expression)
    {
        ArgumentNullException.ThrowIfNull(expression);
        return QueryExecutor.ExecuteAsync(this, expression, expression.Type, CancellationToken.None).GetAwaiter().GetResult();
    }

    public TResult Execute<TResult>(Expression expression)
    {
        ArgumentNullException.ThrowIfNull(expression);
        return ExecuteAsync<TResult>(expression, CancellationToken.None).GetAwaiter().GetResult();
    }

    internal async Task<TResult> ExecuteAsync<TResult>(Expression expression, CancellationToken ct) =>
        (TResult)(await QueryExecutor.ExecuteAsync(this, expression, typeof(TResult), ct).ConfigureAwait(false))!;
}
