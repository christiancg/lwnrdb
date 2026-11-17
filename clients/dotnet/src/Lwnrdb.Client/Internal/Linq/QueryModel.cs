using System;
using System.Collections.Immutable;
using System.Linq.Expressions;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal.Linq;

internal enum Terminal
{
    Sequence,
    First,
    FirstOrDefault,
    Single,
    SingleOrDefault,
    ElementAt,
    ElementAtOrDefault,
    Count,
    LongCount,
    Any,
    All,
}

internal abstract record RowShape(Type ElementType);

internal sealed record EntityShape(IQueryRoot Root) : RowShape(Root.EntityType);

internal sealed record GroupingShape(Type KeyType, MemberTarget Key, EntityShape Element)
    : RowShape(typeof(System.Linq.IGrouping<,>).MakeGenericType(KeyType, Element.ElementType));

internal sealed record ValueShape(MemberTarget Value) : RowShape(Value.ValueType);

internal sealed record GroupJoinShape(EntityShape Outer, string AsField, EntityShape Inner, LambdaExpression ResultSelector)
    : RowShape(ResultSelector.ReturnType);

internal sealed record QueryModel(
    ImmutableArray<PipelineStep> Steps,
    bool DirtyRead,
    RowShape Shape,
    Terminal Terminal,
    ImmutableArray<LambdaExpression> Projections,
    bool SendsNothing)
{
    public Type ResultElementType => Projections.IsEmpty ? Shape.ElementType : Projections[^1].ReturnType;
}
