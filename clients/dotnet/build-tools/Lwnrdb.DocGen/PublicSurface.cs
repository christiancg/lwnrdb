using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

namespace Lwnrdb.DocGen;

internal static class PublicSurface
{
    private const BindingFlags Declared =
        BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;

    private static readonly HashSet<string> ObjectMembers = new(StringComparer.Ordinal) { "Equals", "GetHashCode", "ToString", "Deconstruct", "<Clone>$", "PrintMembers", "EqualityContract" };

    public static IEnumerable<Type> Types(Assembly assembly) =>
        assembly.GetExportedTypes().Where(type => !type.Namespace!.Contains(".Internal", StringComparison.Ordinal)).OrderBy(DocIds.TypeName, StringComparer.Ordinal);

    public static IEnumerable<MemberInfo> Members(Type type) =>
        type.GetMembers(Declared).Where(member => IsDocumented(type, member)).OrderBy(DocIds.Of, StringComparer.Ordinal);

    private static bool IsDocumented(Type type, MemberInfo member)
    {
        if (ObjectMembers.Contains(member.Name) || member.Name.StartsWith("op_", StringComparison.Ordinal) || IsCompilerGenerated(member))
        {
            return false;
        }
        return member switch
        {
            MethodInfo method => !method.IsSpecialName && Visible(method),
            ConstructorInfo constructor => Visible(constructor) && !IsRecordCopy(type, constructor) && !(type.IsAbstract && type.IsSealed),
            PropertyInfo property => (property.GetMethod is { } get && Visible(get)) || (property.SetMethod is { } set && Visible(set)),
            FieldInfo field => (field.IsPublic || field.IsFamily || field.IsFamilyOrAssembly) && !field.IsSpecialName,
            EventInfo e => e.AddMethod is { } add && Visible(add),
            _ => false,
        };
    }

    private static bool Visible(MethodBase method) => method.IsPublic || method.IsFamily || method.IsFamilyOrAssembly;

    private static bool IsRecordCopy(Type type, ConstructorInfo constructor) =>
        constructor.GetParameters() is [var only] && only.ParameterType == type && !constructor.IsPublic;

    private static bool IsCompilerGenerated(MemberInfo member) =>
        member.CustomAttributes.Any(attribute => attribute.AttributeType.FullName == "System.Runtime.CompilerServices.CompilerGeneratedAttribute")
        && member is not PropertyInfo;
}
