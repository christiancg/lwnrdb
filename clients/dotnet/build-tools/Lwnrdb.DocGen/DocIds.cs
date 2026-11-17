using System;
using System.Linq;
using System.Reflection;
using System.Text;

namespace Lwnrdb.DocGen;

internal static class DocIds
{
    public static string Of(Type type) => "T:" + TypeName(type);

    public static string Of(MemberInfo member) => member switch
    {
        Type type => Of(type),
        ConstructorInfo constructor => "M:" + TypeName(constructor.DeclaringType!) + "." + (constructor.IsStatic ? "#cctor" : "#ctor") + Parameters(constructor),
        MethodInfo method => "M:" + TypeName(method.DeclaringType!) + "." + MethodName(method) + Parameters(method) + Conversion(method),
        PropertyInfo property => "P:" + TypeName(property.DeclaringType!) + "." + property.Name + Parameters(property.GetIndexParameters()),
        FieldInfo field => "F:" + TypeName(field.DeclaringType!) + "." + field.Name,
        EventInfo e => "E:" + TypeName(e.DeclaringType!) + "." + e.Name,
        _ => throw new ArgumentException($"no documentation ID for {member.MemberType}", nameof(member)),
    };

    public static string TypeName(Type type)
    {
        if (type.IsNested)
        {
            return TypeName(type.DeclaringType!) + "." + type.Name;
        }
        return string.IsNullOrEmpty(type.Namespace) ? type.Name : type.Namespace + "." + type.Name;
    }

    private static string MethodName(MethodInfo method) =>
        method.IsGenericMethodDefinition ? method.Name + "``" + method.GetGenericArguments().Length : method.Name;

    private static string Conversion(MethodInfo method) =>
        method.Name is "op_Implicit" or "op_Explicit" ? "~" + Parameter(method.ReturnType) : string.Empty;

    private static string Parameters(MethodBase method) => Parameters(method.GetParameters());

    private static string Parameters(ParameterInfo[] parameters) =>
        parameters.Length == 0 ? string.Empty : "(" + string.Join(",", parameters.Select(parameter => Parameter(parameter.ParameterType))) + ")";

    private static string Parameter(Type type)
    {
        if (type.IsByRef)
        {
            return Parameter(type.GetElementType()!) + "@";
        }
        if (type.IsPointer)
        {
            return Parameter(type.GetElementType()!) + "*";
        }
        if (type.IsArray)
        {
            var rank = type.GetArrayRank();
            return Parameter(type.GetElementType()!) + (rank == 1 ? "[]" : "[" + string.Join(",", Enumerable.Repeat("0:", rank)) + "]");
        }
        if (type.IsGenericParameter)
        {
            return (type.DeclaringMethod is null ? "`" : "``") + type.GenericParameterPosition;
        }
        if (!type.IsGenericType)
        {
            return TypeName(type);
        }
        return GenericName(type);
    }

    private static string GenericName(Type type)
    {
        var arguments = type.GetGenericArguments();
        var builder = new StringBuilder();
        var consumed = 0;
        foreach (var segment in Chain(type))
        {
            if (builder.Length > 0)
            {
                builder.Append('.');
            }
            else if (!string.IsNullOrEmpty(segment.Namespace))
            {
                builder.Append(segment.Namespace).Append('.');
            }
            var tick = segment.Name.IndexOf('`', StringComparison.Ordinal);
            if (tick < 0)
            {
                builder.Append(segment.Name);
                continue;
            }
            var count = int.Parse(segment.Name[(tick + 1)..], System.Globalization.CultureInfo.InvariantCulture);
            builder.Append(segment.Name[..tick])
                .Append('{')
                .Append(string.Join(",", arguments.Skip(consumed).Take(count).Select(Parameter)))
                .Append('}');
            consumed += count;
        }
        return builder.ToString();
    }

    private static Type[] Chain(Type type)
    {
        var chain = new System.Collections.Generic.List<Type>();
        for (var current = type.IsGenericType ? type.GetGenericTypeDefinition() : type; current is not null; current = current.DeclaringType)
        {
            chain.Insert(0, current);
        }
        return [.. chain];
    }
}
