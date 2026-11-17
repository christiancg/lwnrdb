using System;
using System.Globalization;

namespace Lwnrdb.Client.Values;

public readonly record struct GeoPoint
{
    private const double MaxLatitude = 90;
    private const double MaxLongitude = 180;

    public GeoPoint(double latitude, double longitude)
    {
        if (!double.IsFinite(latitude) || Math.Abs(latitude) > MaxLatitude)
        {
            throw new ArgumentOutOfRangeException(nameof(latitude), latitude, "latitude must be between -90 and 90");
        }
        if (!double.IsFinite(longitude) || Math.Abs(longitude) > MaxLongitude)
        {
            throw new ArgumentOutOfRangeException(nameof(longitude), longitude, "longitude must be between -180 and 180");
        }
        Latitude = latitude;
        Longitude = longitude;
    }

    public double Latitude { get; }

    public double Longitude { get; }

    public override string ToString() =>
        string.Create(CultureInfo.InvariantCulture, $"GeoPoint({Latitude}, {Longitude})");
}
