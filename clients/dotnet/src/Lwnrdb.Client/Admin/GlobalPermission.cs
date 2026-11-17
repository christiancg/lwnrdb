using System.Diagnostics.CodeAnalysis;

namespace Lwnrdb.Client.Admin;

[SuppressMessage("Naming", "CA1711")]
public enum GlobalPermission
{
    CreateDatabase,
    DropDatabase,
}
