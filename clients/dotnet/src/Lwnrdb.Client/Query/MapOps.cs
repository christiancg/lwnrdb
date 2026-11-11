namespace Lwnrdb.Client.Query;

public static class MapOps
{
    public static MapOperation Set(string field, MidOperation operation) =>
        new(Checks.Text(field, nameof(field)), Checks.NotNull(operation, nameof(operation)), null);

    public static MapOperation Remove(string field) => new(Checks.Text(field, nameof(field)), null, null);
}
