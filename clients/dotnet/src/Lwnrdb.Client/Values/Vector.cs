using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Lwnrdb.Client.Values;

public sealed class Vector : IEquatable<Vector>
{
    private readonly double[] _components;

    private Vector(double[] components)
    {
        if (components.Length == 0)
        {
            throw new ArgumentException("a vector needs at least one component", nameof(components));
        }
        for (var index = 0; index < components.Length; index++)
        {
            if (!double.IsFinite(components[index]))
            {
                throw new ArgumentException(
                    string.Create(CultureInfo.InvariantCulture, $"vector component {index} is not finite: {components[index]}"),
                    nameof(components));
            }
        }
        _components = components;
    }

    public int Dimensions => _components.Length;

    public ReadOnlyMemory<double> Components => _components;

    public double this[int index] => _components[index];

    public static Vector Of(params double[] components)
    {
        ArgumentNullException.ThrowIfNull(components);
        return new Vector([.. components]);
    }

    public static Vector From(IEnumerable<double> components)
    {
        ArgumentNullException.ThrowIfNull(components);
        return new Vector([.. components]);
    }

    public static Vector From(IEnumerable<float> components)
    {
        ArgumentNullException.ThrowIfNull(components);
        return new Vector([.. components.Select(component => (double)component)]);
    }

    public double[] ToArray() => [.. _components];

    public bool Equals(Vector? other) =>
        other is not null && _components.AsSpan().SequenceEqual(other._components);

    public override bool Equals(object? obj) => Equals(obj as Vector);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        foreach (var component in _components)
        {
            hash.Add(component);
        }
        return hash.ToHashCode();
    }

    public override string ToString() =>
        "Vector[" + string.Join(", ", _components.Select(component => component.ToString("R", CultureInfo.InvariantCulture))) + "]";

    public static bool operator ==(Vector? left, Vector? right) => Equals(left, right);

    public static bool operator !=(Vector? left, Vector? right) => !Equals(left, right);
}
