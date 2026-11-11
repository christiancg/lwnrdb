using System.Collections.Generic;

namespace Lwnrdb.Client;

public sealed record ListenUpdate<T>(string ListenId, IReadOnlyList<T> Results, string ResultHash, bool Initial);
