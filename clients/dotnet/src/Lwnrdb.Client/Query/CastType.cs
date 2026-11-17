using System.Diagnostics.CodeAnalysis;

namespace Lwnrdb.Client.Query;

[SuppressMessage("Naming", "CA1720")]
public enum CastType
{
    Number,
    String,
    Boolean,
    JsonCustom,
}
