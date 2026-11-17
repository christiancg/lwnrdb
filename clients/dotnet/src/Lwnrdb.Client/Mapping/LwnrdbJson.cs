using System;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Mapping;

public static class LwnrdbJson
{
    public static JsonSerializerOptions Default { get; } = CreateOptions(null, null);

    public static JsonSerializerOptions CreateOptions(JsonSerializerOptions? userOptions, TimeZoneInfo? temporalZone)
    {
        var options = userOptions is null
            ? new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
                NumberHandling = JsonNumberHandling.Strict,
            }
            : new JsonSerializerOptions(userOptions);
        var resolver = options.TypeInfoResolver is null
            ? new DefaultJsonTypeInfoResolver()
            : JsonTypeInfoResolver.Combine(options.TypeInfoResolver, new DefaultJsonTypeInfoResolver());
        options.TypeInfoResolver = resolver.WithAddedModifier(MapMembers);
        options.Converters.Add(new DateTimeConverter(temporalZone));
        options.Converters.Add(new DateTimeOffsetConverter(temporalZone));
        options.Converters.Add(new TimeOnlyConverter());
        options.Converters.Add(new GeoPointConverter());
        options.Converters.Add(new VectorConverter());
        options.Converters.Add(new GuardedStringConverter());
        options.Converters.Add(new FiniteDoubleConverter());
        options.Converters.Add(new FiniteSingleConverter());
        options.MakeReadOnly();
        return options;
    }

    private static void MapMembers(JsonTypeInfo typeInfo)
    {
        if (typeInfo.Kind != JsonTypeInfoKind.Object)
        {
            return;
        }
        foreach (var property in typeInfo.Properties)
        {
            if (property.AttributeProvider?.IsDefined(typeof(LwnrdbVectorAttribute), true) == true)
            {
                property.CustomConverter = VectorArrayConverter.For(property.PropertyType);
            }
        }
        AliasId(typeInfo);
    }

    private static void AliasId(JsonTypeInfo typeInfo)
    {
        if (typeInfo.Properties.Any(property => property.Name == Document.IdField))
        {
            return;
        }
        var id = typeInfo.Properties.FirstOrDefault(property =>
            property.AttributeProvider is MemberInfo member && member.Name == "Id" && !HasExplicitName(member));
        if (id is null)
        {
            return;
        }
        id.Name = Document.IdField;
        id.ShouldSerialize = static (_, value) => value is not null;
    }

    private static bool HasExplicitName(MemberInfo member) => member.IsDefined(typeof(JsonPropertyNameAttribute), true);
}
