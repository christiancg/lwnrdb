using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Xunit;

namespace Lwnrdb.Client.Tests.Structure;

public sealed class NoInternalLeakTest
{
    private const BindingFlags Visible = BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;

    [Fact]
    public void NoPublicSignatureNamesAnInternalType()
    {
        var leaks = typeof(LwnrdbClient).Assembly.GetExportedTypes()
            .SelectMany(type => SignatureTypes(type).Where(IsHidden).Select(hidden => $"{type.FullName} exposes {hidden.FullName}"))
            .Distinct()
            .ToList();
        Assert.True(leaks.Count == 0, string.Join('\n', leaks));
    }

    [Fact]
    public void NoInternalNamespaceTypeIsPublic()
    {
        var exposed = typeof(LwnrdbClient).Assembly.GetExportedTypes()
            .Where(type => type.Namespace?.StartsWith("Lwnrdb.Client.Internal", StringComparison.Ordinal) == true)
            .Select(type => type.FullName)
            .ToList();
        Assert.Empty(exposed);
    }

    private static IEnumerable<Type> SignatureTypes(Type type)
    {
        IEnumerable<Type> types = [.. type.GetInterfaces().Where(IsVisibleInterface(type)), .. type.BaseType is null ? [] : new[] { type.BaseType }];
        foreach (var member in type.GetMembers(Visible))
        {
            types = types.Concat(member switch
            {
                MethodBase method => method.GetParameters().Select(parameter => parameter.ParameterType)
                    .Concat(method is MethodInfo info ? [info.ReturnType] : []),
                PropertyInfo property => [property.PropertyType],
                FieldInfo field => [field.FieldType],
                EventInfo e => e.EventHandlerType is null ? [] : [e.EventHandlerType],
                _ => [],
            });
        }
        return types.SelectMany(Flatten);
    }

    private static Func<Type, bool> IsVisibleInterface(Type type) =>
        candidate => type.GetInterfaceMap(candidate).TargetMethods.Any(method => method.IsPublic);

    private static IEnumerable<Type> Flatten(Type type)
    {
        var element = type.HasElementType ? type.GetElementType()! : type;
        yield return element;
        if (element.IsGenericType)
        {
            foreach (var argument in element.GetGenericArguments().SelectMany(Flatten))
            {
                yield return argument;
            }
        }
    }

    private static bool IsHidden(Type type) =>
        !type.IsGenericParameter && type.Assembly == typeof(LwnrdbClient).Assembly && !type.IsVisible;
}
