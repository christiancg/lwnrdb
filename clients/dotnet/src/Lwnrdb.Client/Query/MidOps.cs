using System;
using System.Collections.Immutable;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Query;

public static class MidOps
{
    public static MidOperation Avg(params object?[] operands) => Array(ArrayOperator.Avg, operands);

    public static MidOperation Sum(params object?[] operands) => Array(ArrayOperator.Sum, operands);

    public static MidOperation Subs(params object?[] operands) => Array(ArrayOperator.Subs, operands);

    public static MidOperation Max(params object?[] operands) => Array(ArrayOperator.Max, operands);

    public static MidOperation Min(params object?[] operands) => Array(ArrayOperator.Min, operands);

    public static MidOperation Multiply(params object?[] operands) => Array(ArrayOperator.Multiply, operands);

    public static MidOperation Divide(params object?[] operands) => Array(ArrayOperator.Divide, operands);

    public static MidOperation Pow(params object?[] operands) => Array(ArrayOperator.Pow, operands);

    public static MidOperation Root(params object?[] operands) => Array(ArrayOperator.Root, operands);

    public static MidOperation Concat(params object?[] operands) => Array(ArrayOperator.Concat, operands);

    public static Literal Literal(string text) => new(Checks.Text(text, nameof(text)));

    public static MidOperation Abs(string field) => new FieldFunctionOperation(FieldFunction.Abs, Checks.Text(field, nameof(field)));

    public static MidOperation Size(string field) => new FieldFunctionOperation(FieldFunction.Size, Checks.Text(field, nameof(field)));

    public static MidOperation Cast(string field, CastType type)
    {
        if (type == CastType.JsonCustom || !Enum.IsDefined(type))
        {
            throw new ArgumentException($"cast takes Number, String or Boolean, or a CustomType: {type}", nameof(type));
        }
        return new CastOperation(Checks.Text(field, nameof(field)), type, null);
    }

    public static MidOperation Cast(string field, CustomType type)
    {
        if (!Enum.IsDefined(type))
        {
            throw new ArgumentException($"not a custom type: {type}", nameof(type));
        }
        return new CastOperation(Checks.Text(field, nameof(field)), CastType.JsonCustom, type);
    }

    public static MidOperation Script(string source) => new ScriptOperation(Checks.Text(source, nameof(source)));

    private static ArrayOperation Array(ArrayOperator arrayOperator, object?[] operands)
    {
        ArgumentNullException.ThrowIfNull(operands);
        if (operands.Length == 0)
        {
            throw new ArgumentException($"{arrayOperator} needs at least one operand", nameof(operands));
        }
        return new ArrayOperation(arrayOperator, ImmutableArray.Create(operands));
    }
}
