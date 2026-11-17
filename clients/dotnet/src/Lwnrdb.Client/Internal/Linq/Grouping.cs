using System.Collections;
using System.Collections.Generic;
using System.Linq;

namespace Lwnrdb.Client.Internal.Linq;

internal sealed class Grouping<TKey, TElement>(TKey key, IList elements) : IGrouping<TKey, TElement>
{
    private readonly List<TElement> _elements = [.. elements.Cast<TElement>()];

    public TKey Key { get; } = key;

    public IEnumerator<TElement> GetEnumerator() => _elements.GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    public override string ToString() => $"Grouping({Key}, {_elements.Count} elements)";
}
