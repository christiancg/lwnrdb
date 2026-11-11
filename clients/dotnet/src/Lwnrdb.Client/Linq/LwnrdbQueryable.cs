using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Threading;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Internal.Linq;

namespace Lwnrdb.Client.Linq;

public sealed class LwnrdbQueryable<T> : IOrderedQueryable<T>, IAsyncEnumerable<T>, ILwnrdbQueryable
{
    private readonly LwnrdbQueryProvider _provider;

    internal LwnrdbQueryable(LwnrdbQueryProvider provider, Expression? expression)
    {
        _provider = provider;
        Expression = expression ?? Expression.Constant(this);
    }

    public Type ElementType => typeof(T);

    public Expression Expression { get; }

    public IQueryProvider Provider => _provider;

    LwnrdbQueryProvider ILwnrdbQueryable.Provider => _provider;

    bool ILwnrdbQueryable.IsRoot => Expression is ConstantExpression constant && ReferenceEquals(constant.Value, this);

    public IEnumerator<T> GetEnumerator() => _provider.Execute<IEnumerable<T>>(Expression).GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    public async IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)
    {
        var rows = await _provider.ExecuteAsync<IEnumerable<T>>(Expression, cancellationToken).ConfigureAwait(false);
        foreach (var row in rows)
        {
            yield return row;
        }
    }

    public override string ToString() =>
        ((ILwnrdbQueryable)this).IsRoot ? $"{_provider.Root.Database}.{_provider.Root.Collection}" : Expression.ToString();

    internal static LwnrdbQueryable<T> Over(IQueryTarget<T> target) => new(new LwnrdbQueryProvider(new QueryRoot<T>(target)), null);
}
