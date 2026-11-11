using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Linq.Expressions;
using System.Text.Json;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Internal.Linq;

internal sealed class QueryModelBuilder(LwnrdbQueryProvider provider)
{
    private const string GroupByOnlyKey = "after GroupBy only the group's Key can be filtered or sorted on the server";

    private static readonly Dictionary<string, Terminal> Terminals = new()
    {
        [nameof(Queryable.Count)] = Terminal.Count,
        [nameof(Queryable.LongCount)] = Terminal.LongCount,
        [nameof(Queryable.Any)] = Terminal.Any,
        [nameof(Queryable.All)] = Terminal.All,
        [nameof(Queryable.First)] = Terminal.First,
        [nameof(Queryable.FirstOrDefault)] = Terminal.FirstOrDefault,
        [nameof(Queryable.Single)] = Terminal.Single,
        [nameof(Queryable.SingleOrDefault)] = Terminal.SingleOrDefault,
        [nameof(Queryable.ElementAt)] = Terminal.ElementAt,
        [nameof(Queryable.ElementAtOrDefault)] = Terminal.ElementAtOrDefault,
    };

    private static readonly Dictionary<string, string> Refusals = new()
    {
        [nameof(Queryable.ThenBy)] = "the server sorts by one field; sort by a single key, or sort the results in memory",
        [nameof(Queryable.ThenByDescending)] = "the server sorts by one field; sort by a single key, or sort the results in memory",
        [nameof(Queryable.Join)] = "the server's JOIN is a group join; use GroupJoin(inner, outerKey, innerKey, (o, matches) => ...)",
        [nameof(Queryable.Sum)] = "aggregate in memory after ToListAsync, or use the fluent Reduce",
        [nameof(Queryable.Average)] = "aggregate in memory after ToListAsync, or use the fluent Reduce",
        [nameof(Queryable.Min)] = "aggregate in memory after ToListAsync, or use the fluent Reduce",
        [nameof(Queryable.Max)] = "aggregate in memory after ToListAsync, or use the fluent Reduce",
        [nameof(Queryable.Aggregate)] = "aggregate in memory after ToListAsync, or use the fluent Reduce",
    };

    private readonly JsonSerializerOptions _options = provider.Root.Context.Json;
    private readonly ImmutableArray<PipelineStep>.Builder _steps = ImmutableArray.CreateBuilder<PipelineStep>();
    private readonly ImmutableArray<LambdaExpression>.Builder _projections = ImmutableArray.CreateBuilder<LambdaExpression>();
    private RowShape _shape = new EntityShape(provider.Root);
    private Terminal _terminal = Terminal.Sequence;
    private bool _dirtyRead;
    private bool _sendsNothing;

    public QueryModel Build(Expression expression)
    {
        Visit(expression, isTop: true);
        return new QueryModel(_steps.ToImmutable(), _dirtyRead, _shape, _terminal, _projections.ToImmutable(), _sendsNothing);
    }

    private void Visit(Expression expression, bool isTop)
    {
        if (expression is ConstantExpression { Value: ILwnrdbQueryable source } && source.IsRoot && source.Provider == provider)
        {
            return;
        }
        if (expression is not MethodCallExpression call || !IsOperator(call))
        {
            throw TranslationFailure.For(expression, "it is not a query over a LWNRDB collection");
        }
        Visit(call.Arguments[0], isTop: false);
        if (Terminals.TryGetValue(call.Method.Name, out var terminal) && call.Method.DeclaringType == typeof(Queryable))
        {
            if (!isTop)
            {
                throw TranslationFailure.For(call, "a terminal operator must come last");
            }
            ApplyTerminal(call, terminal);
            return;
        }
        Apply(call);
    }

    private static bool IsOperator(MethodCallExpression call) =>
        call.Method.DeclaringType == typeof(Queryable) || call.Method.DeclaringType == typeof(LwnrdbQueryableExtensions);

    private void Apply(MethodCallExpression call)
    {
        switch (call.Method.Name)
        {
            case nameof(Queryable.Where):
                AddFilter(call, Lambda(call, 1));
                break;
            case nameof(Queryable.OrderBy) or nameof(Queryable.OrderByDescending):
                Sort(call);
                break;
            case nameof(Queryable.Skip):
                Skip(Integer(call, 1));
                break;
            case nameof(Queryable.Take):
                Take(Integer(call, 1));
                break;
            case nameof(Queryable.Distinct):
                Distinct(call);
                break;
            case nameof(Queryable.Select):
                _projections.Add(Lambda(call, 1));
                break;
            case nameof(Queryable.GroupBy):
                GroupBy(call);
                break;
            case nameof(Queryable.GroupJoin):
                GroupJoin(call);
                break;
            default:
                ApplyExtension(call);
                break;
        }
    }

    private void ApplyExtension(MethodCallExpression call)
    {
        switch (call.Method.Name)
        {
            case nameof(LwnrdbQueryableExtensions.Filter) when call.Method.DeclaringType == typeof(LwnrdbQueryableExtensions):
                RequireEntity(call);
                _steps.Add(new FilterStep((Filter)Value(call, 1)!));
                break;
            case nameof(LwnrdbQueryableExtensions.Nearest) when call.Method.DeclaringType == typeof(LwnrdbQueryableExtensions):
                Nearest(call);
                break;
            case nameof(LwnrdbQueryableExtensions.DirtyRead) when call.Method.DeclaringType == typeof(LwnrdbQueryableExtensions):
                _dirtyRead = true;
                break;
            default:
                throw TranslationFailure.For(
                    call,
                    Refusals.GetValueOrDefault(call.Method.Name, $"{call.Method.Name} has no server equivalent"));
        }
    }

    private void ApplyTerminal(MethodCallExpression call, Terminal terminal)
    {
        _terminal = terminal;
        var predicate = call.Arguments.Count == 2 && Unquoted(call.Arguments[1]) is LambdaExpression lambda ? lambda : null;
        if (call.Arguments.Count == 2 && predicate is null && terminal is not (Terminal.ElementAt or Terminal.ElementAtOrDefault))
        {
            throw TranslationFailure.For(call, "the overload with a default value has no server equivalent");
        }
        switch (terminal)
        {
            case Terminal.All:
                RequireNoProjection(call);
                _steps.Add(new FilterStep(new ConjunctionFilter(Conjunction.Nor, [Translator(predicate!, call).Translate(predicate!.Body)])));
                _steps.Add(new LimitStep(1));
                return;
            case Terminal.ElementAt or Terminal.ElementAtOrDefault:
                Skip(Integer(call, 1));
                Take(1);
                return;
        }
        if (predicate is not null)
        {
            AddFilter(call, predicate);
        }
        switch (terminal)
        {
            case Terminal.Count or Terminal.LongCount:
                _steps.Add(new CountStep());
                break;
            case Terminal.Single or Terminal.SingleOrDefault:
                Take(2);
                break;
            default:
                Take(1);
                break;
        }
    }

    private void AddFilter(Expression call, LambdaExpression predicate)
    {
        RequireNoProjection(call);
        _steps.Add(new FilterStep(Translator(predicate, call).Translate(predicate.Body)));
    }

    private void Sort(MethodCallExpression call)
    {
        RequireNoProjection(call);
        if (call.Arguments.Count != 2)
        {
            throw TranslationFailure.For(call, "a custom comparer cannot run on the server");
        }
        var selector = Lambda(call, 1);
        var key = Resolver(selector, call)(selector.Body)
            ?? throw TranslationFailure.For(call, "the sort key must be a stored member");
        _steps.Add(new SortStep(key.Path, call.Method.Name == nameof(Queryable.OrderBy)));
    }

    private void Skip(int count)
    {
        if (count > 0)
        {
            _steps.Add(new SkipStep(count));
        }
    }

    private void Take(int count)
    {
        if (count <= 0)
        {
            _sendsNothing = true;
            return;
        }
        _steps.Add(new LimitStep(count));
    }

    private void Distinct(MethodCallExpression call)
    {
        if (call.Arguments.Count != 1)
        {
            throw TranslationFailure.For(call, "a custom comparer cannot run on the server");
        }
        if (_projections.Count == 0 && _shape is EntityShape)
        {
            _steps.Add(new DistinctStep(null));
            return;
        }
        if (_projections.Count == 1 && _shape is EntityShape
            && MemberPaths.TryResolve(_projections[0].Body, _projections[0].Parameters[0], _options) is { } value)
        {
            _steps.Add(new DistinctStep(value.Path));
            _shape = new ValueShape(value);
            _projections.Clear();
            return;
        }
        throw TranslationFailure.For(call, "Distinct runs on whole documents, or on Select(o => o.Member)");
    }

    private void GroupBy(MethodCallExpression call)
    {
        RequireEntity(call);
        if (call.Arguments.Count != 2)
        {
            throw TranslationFailure.For(call, "GroupBy takes only a key selector; shape the groups with a final Select");
        }
        var selector = Lambda(call, 1);
        var key = MemberPaths.Resolve(selector.Body, selector.Parameters[0], _options, "the group key");
        _steps.Add(new GroupByStep(key.Path));
        _shape = new GroupingShape(selector.Body.Type, key, (EntityShape)_shape);
    }

    private void GroupJoin(MethodCallExpression call)
    {
        RequireEntity(call);
        if (call.Arguments.Count != 5)
        {
            throw TranslationFailure.For(call, "a custom comparer cannot run on the server");
        }
        var inner = InnerRoot(call);
        var outerKey = Lambda(call, 2);
        var innerKey = Lambda(call, 3);
        var resultSelector = Lambda(call, 4);
        var local = MemberPaths.Resolve(outerKey.Body, outerKey.Parameters[0], _options, "the outer key");
        var remote = MemberPaths.Resolve(innerKey.Body, innerKey.Parameters[0], inner.Context.Json, "the inner key");
        var asField = resultSelector.Parameters[1].Name
            ?? throw TranslationFailure.For(call, "the result selector's second parameter needs a name, which names the joined field");
        _steps.Add(new JoinStep(inner.Collection, local.Path, remote.Path, asField));
        _shape = new GroupJoinShape((EntityShape)_shape, asField, new EntityShape(inner), resultSelector);
    }

    private IQueryRoot InnerRoot(MethodCallExpression call)
    {
        if (call.Arguments[1] is not ConstantExpression { Value: ILwnrdbQueryable inner })
        {
            throw TranslationFailure.For(call, "the inner source must be another collection's AsQueryable(), with no operators");
        }
        if (!inner.IsRoot)
        {
            throw TranslationFailure.For(call, "the inner source has operators; the server joins a whole collection");
        }
        if (inner.Provider.Root.Database != provider.Root.Database)
        {
            throw TranslationFailure.For(call, "the inner collection belongs to another database; the server joins within one database");
        }
        return inner.Provider.Root;
    }

    private void Nearest(MethodCallExpression call)
    {
        RequireEntity(call);
        var selector = Lambda(call, 1);
        var field = MemberPaths.Resolve(selector.Body, selector.Parameters[0], _options, "the vector member");
        var nearest = Filters.Nearest(field.Path, (Vector)Value(call, 2)!, (int)Value(call, 3)!);
        _steps.Add(new FilterStep((bool)Value(call, 4)! ? nearest.Exact() : nearest));
    }

    private PredicateTranslator Translator(LambdaExpression lambda, Expression call) => new(Resolver(lambda, call), _options);

    private Func<Expression, MemberTarget?> Resolver(LambdaExpression lambda, Expression call)
    {
        var parameter = lambda.Parameters[0];
        return _shape switch
        {
            EntityShape => expression => MemberPaths.TryResolve(expression, parameter, _options),
            GroupingShape grouping => expression =>
                MemberPaths.StripConvert(expression) is MemberExpression { Member.Name: "Key" } key && key.Expression == parameter
                    ? grouping.Key
                    : null,
            _ => throw TranslationFailure.For(call, "the rows are already shaped client-side here; filter and sort before GroupJoin or Distinct"),
        };
    }

    private void RequireNoProjection(Expression call)
    {
        if (_projections.Count > 0)
        {
            throw TranslationFailure.For(call, "Select runs client-side, so it must come after every filter and sort");
        }
    }

    private void RequireEntity(Expression call)
    {
        RequireNoProjection(call);
        if (_shape is not EntityShape)
        {
            throw TranslationFailure.For(call, _shape is GroupingShape ? GroupByOnlyKey : "it must come before GroupJoin or Distinct");
        }
    }

    private static LambdaExpression Lambda(MethodCallExpression call, int index)
    {
        if (Unquoted(call.Arguments[index]) is not LambdaExpression lambda)
        {
            throw TranslationFailure.For(call, "the argument must be a lambda");
        }
        if (call.Method.Name is not nameof(Queryable.GroupJoin) && lambda.Parameters.Count != 1)
        {
            throw TranslationFailure.For(call, "the overload with an index parameter has no server equivalent");
        }
        return lambda;
    }

    private static Expression Unquoted(Expression expression) =>
        expression is UnaryExpression { NodeType: ExpressionType.Quote } quote ? quote.Operand : expression;

    private static object? Value(MethodCallExpression call, int index) =>
        call.Arguments[index] is ConstantExpression constant
            ? constant.Value
            : throw TranslationFailure.For(call, $"argument {index} must be a value known before the query runs");

    private static int Integer(MethodCallExpression call, int index) =>
        Value(call, index) is int number ? number : throw TranslationFailure.For(call, "the count must be an int");
}
