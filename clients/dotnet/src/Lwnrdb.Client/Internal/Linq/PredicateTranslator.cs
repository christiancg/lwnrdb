using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Linq.Expressions;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Internal.Linq;

internal sealed class PredicateTranslator(Func<Expression, MemberTarget?> resolve, JsonSerializerOptions options)
{
    public Filter Translate(Expression predicate) => predicate switch
    {
        BinaryExpression { NodeType: ExpressionType.AndAlso } => Conjunction(Query.Conjunction.And, predicate, ExpressionType.AndAlso),
        BinaryExpression { NodeType: ExpressionType.OrElse } => Conjunction(Query.Conjunction.Or, predicate, ExpressionType.OrElse),
        BinaryExpression { NodeType: ExpressionType.ExclusiveOr } => Conjunction(Query.Conjunction.Xor, predicate, ExpressionType.ExclusiveOr),
        BinaryExpression binary when Comparisons.ContainsKey(binary.NodeType) => Comparison(binary, binary.NodeType),
        UnaryExpression { NodeType: ExpressionType.Not } not => Negation(not.Operand, not),
        MethodCallExpression call => Call(call),
        MemberExpression member when IsHasValue(member) => Field(FieldOperator.NotEqual, Target(member.Expression!, member), null),
        _ when predicate.Type == typeof(bool) && resolve(predicate) is { } flag => Field(FieldOperator.Equal, flag, true),
        _ => throw TranslationFailure.For(predicate, "only comparisons of stored members with values can run on the server"),
    };

    private static readonly Dictionary<ExpressionType, FieldOperator> Comparisons = new()
    {
        [ExpressionType.Equal] = FieldOperator.Equal,
        [ExpressionType.NotEqual] = FieldOperator.NotEqual,
        [ExpressionType.GreaterThan] = FieldOperator.GreaterThan,
        [ExpressionType.GreaterThanOrEqual] = FieldOperator.GreaterThanOrEqual,
        [ExpressionType.LessThan] = FieldOperator.SmallerThan,
        [ExpressionType.LessThanOrEqual] = FieldOperator.SmallerThanOrEqual,
    };

    private static readonly Dictionary<ExpressionType, ExpressionType> Flipped = new()
    {
        [ExpressionType.Equal] = ExpressionType.Equal,
        [ExpressionType.NotEqual] = ExpressionType.NotEqual,
        [ExpressionType.GreaterThan] = ExpressionType.LessThan,
        [ExpressionType.GreaterThanOrEqual] = ExpressionType.LessThanOrEqual,
        [ExpressionType.LessThan] = ExpressionType.GreaterThan,
        [ExpressionType.LessThanOrEqual] = ExpressionType.GreaterThanOrEqual,
    };

    private static readonly Dictionary<ExpressionType, GeoDistanceComparator> DistanceComparators = new()
    {
        [ExpressionType.Equal] = GeoDistanceComparator.Equal,
        [ExpressionType.GreaterThan] = GeoDistanceComparator.GreaterThan,
        [ExpressionType.GreaterThanOrEqual] = GeoDistanceComparator.GreaterThanOrEqual,
        [ExpressionType.LessThan] = GeoDistanceComparator.SmallerThan,
        [ExpressionType.LessThanOrEqual] = GeoDistanceComparator.SmallerThanOrEqual,
    };

    private ConjunctionFilter Conjunction(Conjunction conjunction, Expression predicate, ExpressionType chain) =>
        new(conjunction, [.. Flatten(predicate, chain).Select(Translate)]);

    private static IEnumerable<Expression> Flatten(Expression expression, ExpressionType chain) =>
        expression is BinaryExpression binary && binary.NodeType == chain
            ? Flatten(binary.Left, chain).Concat(Flatten(binary.Right, chain))
            : [expression];

    private Filter Negation(Expression operand, Expression whole) => operand switch
    {
        BinaryExpression { NodeType: ExpressionType.AndAlso } => new ConjunctionFilter(
            Query.Conjunction.Nand,
            [.. Flatten(operand, ExpressionType.AndAlso).Select(Translate)]),
        BinaryExpression { NodeType: ExpressionType.OrElse } => new ConjunctionFilter(
            Query.Conjunction.Nor,
            [.. Flatten(operand, ExpressionType.OrElse).Select(Translate)]),
        BinaryExpression { NodeType: ExpressionType.Equal } equal when !IsDistance(equal) => Comparison(equal, ExpressionType.NotEqual),
        BinaryExpression { NodeType: ExpressionType.NotEqual } notEqual when !IsDistance(notEqual) => Comparison(notEqual, ExpressionType.Equal),
        MemberExpression member when IsHasValue(member) => Field(FieldOperator.Equal, Target(member.Expression!, whole), null),
        MethodCallExpression call when IsContains(call) && IsValueListContains(call) => Contains(call, negated: true),
        _ when operand.Type == typeof(bool) && resolve(operand) is { } flag => Field(FieldOperator.Equal, flag, false),
        _ => new ConjunctionFilter(Query.Conjunction.Nor, [Translate(operand)]),
    };

    private Filter Comparison(BinaryExpression binary, ExpressionType nodeType)
    {
        if (IsDistance(binary))
        {
            return Distance(binary, nodeType);
        }
        var left = resolve(binary.Left);
        var right = resolve(binary.Right);
        if (left is not null && right is not null)
        {
            throw TranslationFailure.For(binary, "the server compares a member with a value, not with another member");
        }
        if (left is null && right is null)
        {
            throw TranslationFailure.For(binary, "one side must be a stored member of the queried type");
        }
        var (target, value, effective) = left is not null
            ? (left, binary.Right, nodeType)
            : (right!, binary.Left, Flipped[nodeType]);
        return Field(Comparisons[effective], target, Constant(value, binary));
    }

    private DistanceFilter Distance(BinaryExpression binary, ExpressionType nodeType)
    {
        var distanceOnLeft = IsDistanceCall(binary.Left);
        var call = (MethodCallExpression)MemberPaths.StripConvert(distanceOnLeft ? binary.Left : binary.Right);
        var effective = distanceOnLeft ? nodeType : Flipped[nodeType];
        if (!DistanceComparators.TryGetValue(effective, out var comparator))
        {
            throw TranslationFailure.For(binary, "a distance can be compared with ==, <, <=, > or >=, not with !=");
        }
        var metres = Convert.ToDouble(Constant(distanceOnLeft ? binary.Right : binary.Left, binary), System.Globalization.CultureInfo.InvariantCulture);
        var target = Target(call.Arguments[0], binary);
        var point = Constant(call.Arguments[1], binary) as GeoPoint?
            ?? throw TranslationFailure.For(binary, "the distance target must be a GeoPoint value");
        return (DistanceFilter)Filters.Distance(target.Path, point, comparator, metres);
    }

    private Filter Call(MethodCallExpression call)
    {
        if (call.Method.DeclaringType == typeof(LwnrdbFunctions))
        {
            return Function(call);
        }
        if (call.Method.Name == nameof(string.Equals))
        {
            return StringEquals(call);
        }
        if (IsContains(call))
        {
            return Contains(call, negated: false);
        }
        throw TranslationFailure.For(call, $"{call.Method.Name} cannot run on the server");
    }

    private Filter Function(MethodCallExpression call)
    {
        switch (call.Method.Name)
        {
            case nameof(LwnrdbFunctions.Within):
                var polygon = Constant(call.Arguments[1], call) as GeoPoint[]
                    ?? throw TranslationFailure.For(call, "the polygon must be GeoPoint values");
                return Filters.Within(Target(call.Arguments[0], call).Path, polygon);
            case nameof(LwnrdbFunctions.Script):
                return Filters.Script(Constant(call.Arguments[0], call) as string
                    ?? throw TranslationFailure.For(call, "the script must be a string value"));
            default:
                throw TranslationFailure.For(call, "LwnrdbFunctions.Distance must be compared with a number of metres");
        }
    }

    private FieldFilter StringEquals(MethodCallExpression call)
    {
        var operands = call.Object is null ? call.Arguments.Take(2).ToList() : [call.Object, call.Arguments[0]];
        var comparison = call.Arguments.Count > (call.Object is null ? 2 : 1) ? Constant(call.Arguments[^1], call) : null;
        if (comparison is not null and not StringComparison.OrdinalIgnoreCase)
        {
            throw TranslationFailure.For(call, "the server compares strings ignoring case; use StringComparison.OrdinalIgnoreCase or ==");
        }
        return (FieldFilter)Comparison(Expression.Equal(operands[0], operands[1]), ExpressionType.Equal);
    }

    private FieldFilter Contains(MethodCallExpression call, bool negated)
    {
        var (collection, item) = call.Object is null ? (call.Arguments[0], call.Arguments[1]) : (call.Object, call.Arguments[0]);
        collection = Unspanned(collection);
        if (collection.Type == typeof(string))
        {
            return StringContains(call, collection, item);
        }
        if (resolve(collection) is { } member)
        {
            var elementType = QueryableTypes.ElementTypeOf(member.ValueType);
            return Field(FieldOperator.Contains, member, ConstantEncoder.EncodeElement(Constant(item, call), elementType, member.Path, options));
        }
        var values = Constant(collection, call);
        if (values is IQueryable or not IEnumerable)
        {
            throw TranslationFailure.For(call, "IN needs a captured array, list or set of values, not a query");
        }
        var target = Target(item, call);
        JsonArray encoded = [.. ((IEnumerable)values).Cast<object?>().Select(value => ConstantEncoder.Encode(value, target, options))];
        return Field(negated ? FieldOperator.NotIn : FieldOperator.In, target, encoded);
    }

    private FieldFilter StringContains(MethodCallExpression call, Expression text, Expression item)
    {
        if (call.Arguments.Count > 1 && Constant(call.Arguments[^1], call) is not StringComparison.Ordinal)
        {
            throw TranslationFailure.For(call, "string Contains runs with StringComparison.Ordinal only");
        }
        var target = Target(text, call);
        return Field(FieldOperator.Contains, target, ConstantEncoder.Encode(Constant(item, call), target, options));
    }

    private static bool IsContains(MethodCallExpression call) => call.Method.Name == nameof(Enumerable.Contains);

    private bool IsValueListContains(MethodCallExpression call)
    {
        var collection = Unspanned(call.Object ?? call.Arguments[0]);
        return collection.Type != typeof(string) && resolve(collection) is null;
    }

    private static bool IsDistance(BinaryExpression binary) => IsDistanceCall(binary.Left) || IsDistanceCall(binary.Right);

    private static bool IsDistanceCall(Expression expression) =>
        MemberPaths.StripConvert(expression) is MethodCallExpression call
        && call.Method.DeclaringType == typeof(LwnrdbFunctions)
        && call.Method.Name == nameof(LwnrdbFunctions.Distance);

    private static bool IsHasValue(MemberExpression member) =>
        member.Member.Name == nameof(Nullable<int>.HasValue)
        && member.Expression is not null
        && Nullable.GetUnderlyingType(member.Expression.Type) is not null;

    private static Expression Unspanned(Expression expression) => expression switch
    {
        MethodCallExpression { Method.Name: "op_Implicit", Arguments.Count: 1 } conversion => conversion.Arguments[0],
        NewExpression { Arguments.Count: 1 } span when span.Type.IsByRefLike => span.Arguments[0],
        _ => MemberPaths.StripConvert(expression),
    };

    private MemberTarget Target(Expression expression, Expression whole) =>
        resolve(expression) ?? throw TranslationFailure.For(whole, $"'{expression}' is not a stored member of the queried type");

    private static object? Constant(Expression expression, Expression whole) =>
        MemberPaths.StripConvert(expression) is ConstantExpression constant
            ? constant.Value
            : expression is ConstantExpression direct
                ? direct.Value
                : throw TranslationFailure.For(whole, $"'{expression}' must be a value known before the query runs");

    private FieldFilter Field(FieldOperator fieldOperator, MemberTarget target, object? value) =>
        new(fieldOperator, target.Path, value as JsonNode ?? ConstantEncoder.Encode(value, target, options));
}
