using System;
using System.Linq.Expressions;

namespace Lwnrdb.Client.Internal.Linq;

internal static class TranslationFailure
{
    public static NotSupportedException For(Expression expression, string reason) =>
        new($"LWNRDB cannot translate '{expression}': {reason}");
}
