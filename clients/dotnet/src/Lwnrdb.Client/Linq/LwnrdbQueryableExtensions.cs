using System;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Linq;

public static class LwnrdbQueryableExtensions
{
    public static async Task<List<T>> ToListAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        [.. await ProviderOf(source).ExecuteAsync<IEnumerable<T>>(source.Expression, ct).ConfigureAwait(false)];

    public static async Task<T[]> ToArrayAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        [.. await ProviderOf(source).ExecuteAsync<IEnumerable<T>>(source.Expression, ct).ConfigureAwait(false)];

    public static Task<T> FirstAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, T>(source, nameof(Queryable.First), null, ct);

    public static Task<T> FirstAsync<T>(this IQueryable<T> source, Expression<Func<T, bool>> predicate, CancellationToken ct = default) =>
        Terminal<T, T>(source, nameof(Queryable.First), predicate, ct);

    public static Task<T?> FirstOrDefaultAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, T?>(source, nameof(Queryable.FirstOrDefault), null, ct);

    public static Task<T?> FirstOrDefaultAsync<T>(
        this IQueryable<T> source,
        Expression<Func<T, bool>> predicate,
        CancellationToken ct = default) =>
        Terminal<T, T?>(source, nameof(Queryable.FirstOrDefault), predicate, ct);

    public static Task<T> SingleAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, T>(source, nameof(Queryable.Single), null, ct);

    public static Task<T> SingleAsync<T>(this IQueryable<T> source, Expression<Func<T, bool>> predicate, CancellationToken ct = default) =>
        Terminal<T, T>(source, nameof(Queryable.Single), predicate, ct);

    public static Task<T?> SingleOrDefaultAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, T?>(source, nameof(Queryable.SingleOrDefault), null, ct);

    public static Task<T?> SingleOrDefaultAsync<T>(
        this IQueryable<T> source,
        Expression<Func<T, bool>> predicate,
        CancellationToken ct = default) =>
        Terminal<T, T?>(source, nameof(Queryable.SingleOrDefault), predicate, ct);

    public static Task<T> ElementAtAsync<T>(this IQueryable<T> source, int index, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        var method = QueryableMethod(nameof(Queryable.ElementAt), typeof(int)).MakeGenericMethod(typeof(T));
        return ProviderOf(source).ExecuteAsync<T>(Expression.Call(method, source.Expression, Expression.Constant(index)), ct);
    }

    public static Task<int> CountAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, int>(source, nameof(Queryable.Count), null, ct);

    public static Task<int> CountAsync<T>(this IQueryable<T> source, Expression<Func<T, bool>> predicate, CancellationToken ct = default) =>
        Terminal<T, int>(source, nameof(Queryable.Count), predicate, ct);

    public static Task<long> LongCountAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, long>(source, nameof(Queryable.LongCount), null, ct);

    public static Task<long> LongCountAsync<T>(
        this IQueryable<T> source,
        Expression<Func<T, bool>> predicate,
        CancellationToken ct = default) =>
        Terminal<T, long>(source, nameof(Queryable.LongCount), predicate, ct);

    public static Task<bool> AnyAsync<T>(this IQueryable<T> source, CancellationToken ct = default) =>
        Terminal<T, bool>(source, nameof(Queryable.Any), null, ct);

    public static Task<bool> AnyAsync<T>(this IQueryable<T> source, Expression<Func<T, bool>> predicate, CancellationToken ct = default) =>
        Terminal<T, bool>(source, nameof(Queryable.Any), predicate, ct);

    public static Task<bool> AllAsync<T>(this IQueryable<T> source, Expression<Func<T, bool>> predicate, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(predicate);
        return Terminal<T, bool>(source, nameof(Queryable.All), predicate, ct);
    }

    public static IAsyncEnumerable<T> AsAsyncEnumerable<T>(this IQueryable<T> source)
    {
        ArgumentNullException.ThrowIfNull(source);
        return source as IAsyncEnumerable<T> ?? throw NotLwnrdb();
    }

    public static Task<AnalyzeResult> AnalyzeAsync<T>(this IQueryable<T> source, CancellationToken ct = default)
    {
        var provider = ProviderOf(source);
        var model = QueryExecutor.Translate(provider, source.Expression);
        return provider.Root.AnalyzeAsync(model.Steps, model.DirtyRead, ct);
    }

    public static IQueryable<T> Nearest<T>(
        this IQueryable<T> source,
        Expression<Func<T, object?>> member,
        Vector query,
        int k,
        bool exact = false)
    {
        ArgumentNullException.ThrowIfNull(member);
        ArgumentNullException.ThrowIfNull(query);
        var method = ((MethodInfo)MethodBase.GetCurrentMethod()!).MakeGenericMethod(typeof(T));
        return ProviderOf(source).CreateQuery<T>(Expression.Call(
            method,
            source.Expression,
            Expression.Quote(member),
            Expression.Constant(query),
            Expression.Constant(k),
            Expression.Constant(exact)));
    }

    public static IQueryable<T> Filter<T>(this IQueryable<T> source, Filter filter)
    {
        ArgumentNullException.ThrowIfNull(filter);
        var method = ((MethodInfo)MethodBase.GetCurrentMethod()!).MakeGenericMethod(typeof(T));
        return ProviderOf(source).CreateQuery<T>(Expression.Call(method, source.Expression, Expression.Constant(filter, typeof(Filter))));
    }

    public static IQueryable<T> DirtyRead<T>(this IQueryable<T> source)
    {
        var method = ((MethodInfo)MethodBase.GetCurrentMethod()!).MakeGenericMethod(typeof(T));
        return ProviderOf(source).CreateQuery<T>(Expression.Call(method, source.Expression));
    }

    public static Query<T> ToQuery<T>(this IQueryable<T> source)
    {
        var provider = ProviderOf(source);
        var model = QueryExecutor.Translate(provider, source.Expression);
        if (model.Terminal != Internal.Linq.Terminal.Sequence || !model.Projections.IsEmpty || model.Shape is not EntityShape
            || model.SendsNothing || provider.Root.EntityType != typeof(T))
        {
            throw TranslationFailure.For(
                source.Expression,
                "ToQuery needs a query that returns the collection's documents: no Select, GroupBy, GroupJoin, Distinct of a member or Take(0)");
        }
        return (Query<T>)provider.Root.ToQuery(model.Steps, model.DirtyRead);
    }

    private static Task<TResult> Terminal<T, TResult>(
        IQueryable<T> source,
        string name,
        Expression<Func<T, bool>>? predicate,
        CancellationToken ct)
    {
        var provider = ProviderOf(source);
        var expression = predicate is null
            ? Expression.Call(QueryableMethod(name).MakeGenericMethod(typeof(T)), source.Expression)
            : Expression.Call(
                QueryableMethod(name, typeof(Expression<>)).MakeGenericMethod(typeof(T)),
                source.Expression,
                Expression.Quote(predicate));
        return provider.ExecuteAsync<TResult>(expression, ct);
    }

    private static MethodInfo QueryableMethod(string name, Type? secondParameter = null) =>
        typeof(Queryable).GetMethods(BindingFlags.Public | BindingFlags.Static).Single(method =>
            method.Name == name
            && method.GetParameters().Length == (secondParameter is null ? 1 : 2)
            && (secondParameter is null || SameShape(method.GetParameters()[1].ParameterType, secondParameter)));

    private static bool SameShape(Type parameter, Type expected) =>
        parameter == expected || (parameter.IsGenericType && parameter.GetGenericTypeDefinition() == expected);

    private static LwnrdbQueryProvider ProviderOf<T>(IQueryable<T> source)
    {
        ArgumentNullException.ThrowIfNull(source);
        return source.Provider as LwnrdbQueryProvider ?? throw NotLwnrdb();
    }

    private static InvalidOperationException NotLwnrdb() =>
        new("this operator only works on a query from a LWNRDB collection's AsQueryable()");
}
