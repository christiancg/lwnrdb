using System;
using System.Linq.Expressions;
using System.Text.Json;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal.Linq;

internal static class EntityPredicate
{
    public static Filter Translate<T>(Expression<Func<T, bool>> predicate, JsonSerializerOptions options)
    {
        var evaluated = (LambdaExpression)PartialEvaluator.Evaluate(predicate);
        var parameter = evaluated.Parameters[0];
        return new PredicateTranslator(expression => MemberPaths.TryResolve(expression, parameter, options), options)
            .Translate(evaluated.Body);
    }
}
