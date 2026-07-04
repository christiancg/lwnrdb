package org.techhouse.utils;

// A single geographic coordinate. Latitude in [-90, 90], longitude in [-180, 180]; both stored as
// doubles, matching the database's "all numbers are doubles" rule.
public record GeoPoint(double lat, double lng) {
}
