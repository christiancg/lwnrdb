using System;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Reflection;
using Lwnrdb.Client.Linq;

namespace Lwnrdb.Client.Internal.Linq;

internal static class PartialEvaluator
{
    public static Expression Evaluate(Expression expression)
    {
        var evaluable = new Nominator().Nominate(expression);
        return new SubtreeEvaluator(evaluable).Visit(expression)!;
    }

    private static bool CanBeEvaluated(Expression expression) => expression switch
    {
        ParameterExpression => false,
        LambdaExpression => false,
        _ when expression.Type.IsByRefLike => false,
        MethodCallExpression call => !IsQueryOperator(call.Method),
        _ => true,
    };

    private static bool IsQueryOperator(MethodInfo method) =>
        method.DeclaringType == typeof(Queryable)
        || method.DeclaringType == typeof(LwnrdbQueryableExtensions)
        || method.DeclaringType == typeof(LwnrdbFunctions);

    private sealed class Nominator : ExpressionVisitor
    {
        private readonly HashSet<Expression> _candidates = [];
        private bool _blocked;

        public HashSet<Expression> Nominate(Expression expression)
        {
            Visit(expression);
            return _candidates;
        }

        public override Expression? Visit(Expression? node)
        {
            if (node is null)
            {
                return null;
            }
            var blockedBefore = _blocked;
            _blocked = false;
            base.Visit(node);
            if (!_blocked)
            {
                if (CanBeEvaluated(node))
                {
                    _candidates.Add(node);
                }
                else
                {
                    _blocked = true;
                }
            }
            _blocked |= blockedBefore;
            return node;
        }
    }

    private sealed class SubtreeEvaluator(HashSet<Expression> candidates) : ExpressionVisitor
    {
        public override Expression? Visit(Expression? node)
        {
            if (node is null)
            {
                return null;
            }
            if (candidates.Contains(node) && node is not ConstantExpression)
            {
                return Expression.Constant(Value(node), node.Type);
            }
            return base.Visit(node);
        }

        private static object? Value(Expression node)
        {
            if (node is MemberExpression { Expression: ConstantExpression owner } member)
            {
                return member.Member switch
                {
                    FieldInfo field => field.GetValue(owner.Value),
                    PropertyInfo property => property.GetValue(owner.Value),
                    _ => Compiled(node),
                };
            }
            return Compiled(node);
        }

        private static object? Compiled(Expression node)
        {
            try
            {
                return Expression.Lambda<Func<object?>>(Expression.Convert(node, typeof(object)))
                    .Compile(preferInterpretation: true)
                    .Invoke();
            }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            {
                throw error.InnerException;
            }
        }
    }
}
