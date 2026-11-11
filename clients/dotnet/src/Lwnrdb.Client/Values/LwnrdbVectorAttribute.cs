using System;

namespace Lwnrdb.Client.Values;

[AttributeUsage(AttributeTargets.Property | AttributeTargets.Field, AllowMultiple = false)]
public sealed class LwnrdbVectorAttribute : Attribute
{
}
