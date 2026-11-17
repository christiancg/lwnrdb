using System;
using System.Collections.Generic;
using System.Linq;

namespace Lwnrdb.Client.Internal.Linq;

internal static class QueryableTypes
{
    public static Type ElementTypeOf(Type sequenceType)
    {
        if (sequenceType.IsGenericType && sequenceType.GetGenericTypeDefinition() == typeof(IEnumerable<>))
        {
            return sequenceType.GetGenericArguments()[0];
        }
        var enumerable = sequenceType.GetInterfaces()
            .FirstOrDefault(type => type.IsGenericType && type.GetGenericTypeDefinition() == typeof(IEnumerable<>));
        return enumerable?.GetGenericArguments()[0] ?? sequenceType;
    }
}
