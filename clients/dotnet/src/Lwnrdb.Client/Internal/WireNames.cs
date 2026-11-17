using System;
using System.Text;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Internal;

internal static class WireNames
{
    public static string Of(Status status) => Upper(status.ToString());

    public static Status? StatusOf(string? text) => text switch
    {
        null => null,
        "OK" => Status.Ok,
        "ERROR" => Status.Error,
        "NOT_FOUND" => Status.NotFound,
        "NO_OPERATION" => Status.NoOperation,
        "UNAUTHENTICATED" => Status.Unauthenticated,
        "FORBIDDEN" => Status.Forbidden,
        _ => Status.Error,
    };

    public static string Of(FieldOperator fieldOperator) => fieldOperator switch
    {
        FieldOperator.Equal => "EQUALS",
        FieldOperator.NotEqual => "NOT_EQUALS",
        FieldOperator.GreaterThan => "GREATER_THAN",
        FieldOperator.GreaterThanOrEqual => "GREATER_THAN_EQUALS",
        FieldOperator.SmallerThan => "SMALLER_THAN",
        FieldOperator.SmallerThanOrEqual => "SMALLER_THAN_EQUALS",
        FieldOperator.In => "IN",
        FieldOperator.NotIn => "NOT_IN",
        FieldOperator.Contains => "CONTAINS",
        _ => throw new ArgumentOutOfRangeException(nameof(fieldOperator), fieldOperator, null),
    };

    public static string Of(GeoDistanceComparator comparator) => comparator switch
    {
        GeoDistanceComparator.Equal => "EQUALS",
        GeoDistanceComparator.GreaterThan => "GREATER_THAN",
        GeoDistanceComparator.GreaterThanOrEqual => "GREATER_THAN_EQUALS",
        GeoDistanceComparator.SmallerThan => "SMALLER_THAN",
        GeoDistanceComparator.SmallerThanOrEqual => "SMALLER_THAN_EQUALS",
        _ => throw new ArgumentOutOfRangeException(nameof(comparator), comparator, null),
    };

    public static string Of(CustomType type) => type switch
    {
        CustomType.DateTime => "datetime",
        CustomType.Time => "time",
        CustomType.Geo => "geo",
        CustomType.Vector => "vector",
        _ => throw new ArgumentOutOfRangeException(nameof(type), type, null),
    };

    public static string Upper(string pascal)
    {
        var builder = new StringBuilder(pascal.Length + 4);
        for (var index = 0; index < pascal.Length; index++)
        {
            var character = pascal[index];
            if (index > 0 && char.IsUpper(character))
            {
                builder.Append('_');
            }
            builder.Append(char.ToUpperInvariant(character));
        }
        return builder.ToString();
    }
}
