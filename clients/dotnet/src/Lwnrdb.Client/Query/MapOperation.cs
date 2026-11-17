namespace Lwnrdb.Client.Query;

public sealed record MapOperation(string Field, MidOperation? Operation, Filter? Condition)
{
    public bool Removes => Operation is null;

    public MapOperation When(Filter condition) => this with { Condition = Checks.NotNull(condition, nameof(condition)) };
}
