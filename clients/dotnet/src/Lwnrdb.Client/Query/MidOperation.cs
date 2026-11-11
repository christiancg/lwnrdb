using System;
using System.Collections.Immutable;
using System.Linq;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Query;

public abstract record MidOperation;

public sealed record ArrayOperation(ArrayOperator Operator, ImmutableArray<object?> Operands) : MidOperation
{
    public bool Equals(ArrayOperation? other) =>
        other is not null && Operator == other.Operator && Operands.SequenceEqual(other.Operands);

    public override int GetHashCode() => HashCode.Combine(Operator, Operands.Length);
}

public sealed record FieldFunctionOperation(FieldFunction Function, string Field) : MidOperation;

public sealed record CastOperation(string Field, CastType Type, CustomType? CustomType) : MidOperation;

public sealed record ScriptOperation(string Source) : MidOperation;

public sealed record Literal(string Text);
