package io.github.christiancg.lwnrdb.client.values;

public record GeoPoint(double lat, double lng) {
    public static final double MAX_LATITUDE = 90;
    public static final double MAX_LONGITUDE = 180;

    public GeoPoint {
        if (!Double.isFinite(lat) || Math.abs(lat) > MAX_LATITUDE) {
            throw new IllegalArgumentException("latitude must be between -90 and 90, was " + lat);
        }
        if (!Double.isFinite(lng) || Math.abs(lng) > MAX_LONGITUDE) {
            throw new IllegalArgumentException("longitude must be between -180 and 180, was " + lng);
        }
    }

    public static GeoPoint of(double lat, double lng) {
        return new GeoPoint(lat, lng);
    }
}
