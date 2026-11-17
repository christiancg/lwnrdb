using System;
using System.Linq;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Values;

public sealed class VectorTests
{
    [Fact]
    public void AVectorNeedsFiniteComponents()
    {
        Assert.Throws<ArgumentException>(() => Vector.Of());
        Assert.Throws<ArgumentException>(() => Vector.Of(1, double.NaN));
        Assert.Throws<ArgumentException>(() => Vector.From(new[] { float.PositiveInfinity }));
    }

    [Fact]
    public void AVectorIsAnImmutableCopy()
    {
        var source = new[] { 1.0, 2.0 };
        var vector = Vector.Of(source);
        source[0] = 9;
        Assert.Equal(1.0, vector[0]);
        var copy = vector.ToArray();
        copy[1] = 9;
        Assert.Equal(2.0, vector[1]);
        Assert.Equal(2, vector.Dimensions);
        Assert.Equal([1.0, 2.0], vector.Components.ToArray());
    }

    [Fact]
    public void EqualityIsByComponent()
    {
        Assert.Equal(Vector.Of(1, 2), Vector.From([1.0, 2.0]));
        Assert.True(Vector.Of(1, 2) == Vector.From(new[] { 1f, 2f }));
        Assert.True(Vector.Of(1) != Vector.Of(2));
        Assert.False(Vector.Of(1).Equals(null));
        Assert.Equal(Vector.Of(1, 2).GetHashCode(), Vector.Of(1, 2).GetHashCode());
        Assert.Equal("Vector[1, 2.5]", Vector.Of(1, 2.5).ToString());
    }

    [Fact]
    public void NegativeZeroKeepsItsSign()
    {
        var vector = (Vector)CustomValues.Parse("#vector(-0.0,1.0E21,4.4E-4)");
        Assert.True(double.IsNegative(vector[0]));
        Assert.Equal("#vector(-0,1E+21,0.00044)", CustomValues.Format(vector));
        Assert.True(double.IsNegative(((Vector)CustomValues.Parse(CustomValues.Format(vector)))[0]));
        Assert.Equal([0.0, 1e21, 4.4e-4], vector.ToArray().Select(Math.Abs));
    }
}
