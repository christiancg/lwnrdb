using System;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Values;

public sealed class GeoPointTests
{
    [Theory]
    [InlineData(90.1, 0)]
    [InlineData(-90.1, 0)]
    [InlineData(0, 180.1)]
    [InlineData(double.NaN, 0)]
    [InlineData(0, double.PositiveInfinity)]
    public void CoordinatesOutOfRangeAreRefused(double latitude, double longitude) =>
        Assert.Throws<ArgumentOutOfRangeException>(() => new GeoPoint(latitude, longitude));

    [Fact]
    public void APointRoundTripsThroughItsCustomValue()
    {
        var point = new GeoPoint(-90, 180);
        Assert.Equal("#geo(-90,180)", CustomValues.Format(point));
        Assert.Equal(point, CustomValues.Parse("#geo(-90.0, 180.0)"));
        Assert.Equal("GeoPoint(-90, 180)", point.ToString());
    }
}
