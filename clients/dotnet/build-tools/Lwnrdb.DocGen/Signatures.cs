using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

namespace Lwnrdb.DocGen;

internal static class Signatures
{
    private static readonly Dictionary<string, string> Keywords = new(StringComparer.Ordinal)
    {
        ["System.String"] = "string",
        ["System.Int32"] = "int",
        ["System.Int64"] = "long",
        ["System.Double"] = "double",
        ["System.Single"] = "float",
        ["System.Boolean"] = "bool",
        ["System.Object"] = "object",
        ["System.Decimal"] = "decimal",
        ["System.Void"] = "void",
    };

    public static string Of(MemberInfo member) => member switch
    {
        ConstructorInfo constructor => BaseName(constructor.DeclaringType!) + Parameters(constructor.GetParameters()),
        MethodInfo method => method.Name + TypeArguments(method) + Parameters(method.GetParameters()),
        PropertyInfo property when property.GetIndexParameters().Length > 0 =>
            "this[" + string.Join(", ", property.GetIndexParameters().Select(parameter => Display(parameter.ParameterType))) + "]",
        _ => member.Name,
    };

    public static string Display(Type type)
    {
        if (type.IsByRef)
        {
            return "ref " + Display(type.GetElementType()!);
        }
        if (type.IsArray)
        {
            return Display(type.GetElementType()!) + "[]";
        }
        if (type.IsGenericParameter)
        {
            return type.Name;
        }
        if (type.IsGenericType && type.GetGenericTypeDefinition().FullName == "System.Nullable`1")
        {
            return Display(type.GetGenericArguments()[0]) + "?";
        }
        if (type.IsGenericType)
        {
            return BaseName(type) + "<" + string.Join(", ", type.GetGenericArguments().Select(Display)) + ">";
        }
        return type.FullName is { } full && Keywords.TryGetValue(full, out var keyword) ? keyword : type.Name;
    }

    public static string BaseName(Type type)
    {
        var tick = type.Name.IndexOf('`', StringComparison.Ordinal);
        return tick < 0 ? type.Name : type.Name[..tick];
    }

    public static string FileName(Type type) => DocIds.TypeName(type).Replace('`', '-') + ".md";

    private static string TypeArguments(MethodInfo method) =>
        method.IsGenericMethodDefinition ? "<" + string.Join(", ", method.GetGenericArguments().Select(argument => argument.Name)) + ">" : string.Empty;

    private static string Parameters(ParameterInfo[] parameters) =>
        "(" + string.Join(", ", parameters.Select(parameter => Display(parameter.ParameterType))) + ")";
}
