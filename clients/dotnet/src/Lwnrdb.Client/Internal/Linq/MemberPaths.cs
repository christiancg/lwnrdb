using System;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;

namespace Lwnrdb.Client.Internal.Linq;

internal sealed record MemberTarget(string Path, Type ValueType, JsonPropertyInfo? Property);

internal static class MemberPaths
{
    public static MemberTarget? TryResolve(Expression expression, ParameterExpression root, JsonSerializerOptions options)
    {
        var members = new Stack<MemberExpression>();
        var node = StripConvert(expression);
        while (node is MemberExpression member && member.Expression is not null)
        {
            if (!IsNullableValue(member))
            {
                members.Push(member);
            }
            node = StripConvert(member.Expression);
        }
        if (node != root || members.Count == 0)
        {
            return null;
        }
        var names = new List<string>(members.Count);
        JsonPropertyInfo? property = null;
        Type valueType = root.Type;
        foreach (var member in members)
        {
            property = PropertyOf(member, options, expression);
            names.Add(property.Name);
            valueType = member.Type;
        }
        return new MemberTarget(string.Join('.', names), valueType, property);
    }

    public static MemberTarget Resolve(Expression expression, ParameterExpression root, JsonSerializerOptions options, string purpose) =>
        TryResolve(expression, root, options)
            ?? throw TranslationFailure.For(expression, $"{purpose} must be a property of the queried type, such as o => o.Status");

    public static Expression StripConvert(Expression expression)
    {
        while (expression is UnaryExpression { NodeType: ExpressionType.Convert or ExpressionType.ConvertChecked or ExpressionType.TypeAs } unary)
        {
            expression = unary.Operand;
        }
        return expression;
    }

    public static bool IsNullableValue(MemberExpression member) =>
        member.Member.Name == nameof(Nullable<int>.Value)
        && member.Expression is not null
        && Nullable.GetUnderlyingType(member.Expression.Type) is not null;

    private static JsonPropertyInfo PropertyOf(MemberExpression member, JsonSerializerOptions options, Expression whole)
    {
        var ownerType = Nullable.GetUnderlyingType(member.Expression!.Type) ?? member.Expression.Type;
        var typeInfo = options.GetTypeInfo(ownerType);
        if (typeInfo.Kind != JsonTypeInfoKind.Object)
        {
            throw TranslationFailure.For(whole, $"{ownerType.Name} does not map to a JSON object");
        }
        var property = typeInfo.Properties.FirstOrDefault(property => Declares(property, member.Member));
        return property is not null && IsStored(property)
            ? property
            : throw TranslationFailure.For(
                whole,
                $"{ownerType.Name}.{member.Member.Name} is not stored ([JsonIgnore], or not a serialized member)");
    }

    private static bool IsStored(JsonPropertyInfo property) =>
        property.Get is not null
        && property.AttributeProvider?.GetCustomAttributes(typeof(JsonIgnoreAttribute), true)
            .OfType<JsonIgnoreAttribute>()
            .Any(ignore => ignore.Condition == JsonIgnoreCondition.Always) != true;

    private static bool Declares(JsonPropertyInfo property, MemberInfo member) =>
        property.AttributeProvider is MemberInfo declared && declared.Name == member.Name;
}
