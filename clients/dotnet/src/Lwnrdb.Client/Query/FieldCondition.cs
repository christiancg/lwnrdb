using System.Collections.Immutable;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Query;

public sealed class FieldCondition
{
    internal FieldCondition(string field)
    {
        Field = Checks.Text(field, nameof(field));
    }

    public string Field { get; }

    public Filter Eq(object? value) => Of(FieldOperator.Equal, value);

    public Filter Ne(object? value) => Of(FieldOperator.NotEqual, value);

    public Filter Gt(object? value) => Of(FieldOperator.GreaterThan, value);

    public Filter Gte(object? value) => Of(FieldOperator.GreaterThanOrEqual, value);

    public Filter Lt(object? value) => Of(FieldOperator.SmallerThan, value);

    public Filter Lte(object? value) => Of(FieldOperator.SmallerThanOrEqual, value);

    public Filter In(params object?[] values) => OfList(FieldOperator.In, values);

    public Filter NotIn(params object?[] values) => OfList(FieldOperator.NotIn, values);

    public Filter Contains(object? value) => Of(FieldOperator.Contains, value);

    private FieldFilter Of(FieldOperator fieldOperator, object? value) =>
        new(fieldOperator, Field, DocumentValues.Encode(value, Field));

    private FieldFilter OfList(FieldOperator fieldOperator, object?[] values) =>
        new(fieldOperator, Field, DocumentValues.Encode(ImmutableArray.Create(Checks.NotNull(values, nameof(values))), Field));
}
