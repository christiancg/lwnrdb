package org.techhouse.utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.config.Globals;

public final class GeoUtils {
    private GeoUtils() {
    }

    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    // The box must be a superset of the true circle: FILTER can drop a false positive but can never
    // recover a point the box excluded, so it is sized from the same sphere haversineMeters uses.
    private static final double BBOX_SAFETY = 1.001;

    public record BoundingBox(double minLat, double minLng, double maxLat, double maxLng) {
        public boolean contains(GeoPoint point) {
            return point.lat() >= minLat && point.lat() <= maxLat && point.lng() >= minLng && point.lng() <= maxLng;
        }
    }

    public static String geoHash(double lat, double lng, int precision) {
        double latMin = -90;
        double latMax = 90;
        double lngMin = -180;
        double lngMax = 180;
        final var hash = new StringBuilder();
        var even = true;
        var bit = 0;
        var ch = 0;
        while (hash.length() < precision) {
            if (even) {
                final var mid = (lngMin + lngMax) / 2;
                if (lng >= mid) {
                    ch |= 1 << (4 - bit);
                    lngMin = mid;
                } else {
                    lngMax = mid;
                }
            } else {
                final var mid = (latMin + latMax) / 2;
                if (lat >= mid) {
                    ch |= 1 << (4 - bit);
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }
            even = !even;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        final var lat1 = Math.toRadians(a.lat());
        final var lat2 = Math.toRadians(b.lat());
        final var dLat = Math.toRadians(b.lat() - a.lat());
        final var dLng = Math.toRadians(b.lng() - a.lng());
        final var h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * Globals.EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    // Longitude is x and latitude is y here, and the polygon is implicitly closed.
    public static boolean pointInPolygon(GeoPoint point, List<GeoPoint> polygon) {
        final var n = polygon.size();
        var inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final var xi = polygon.get(i).lng();
            final var yi = polygon.get(i).lat();
            final var xj = polygon.get(j).lng();
            final var yj = polygon.get(j).lat();
            final var intersect = ((yi > point.lat()) != (yj > point.lat()))
                    && (point.lng() < (xj - xi) * (point.lat() - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static BoundingBox boundingBoxForRadius(GeoPoint center, double radiusMeters) {
        final var angular = radiusMeters / Globals.EARTH_RADIUS_METERS;
        final var latDelta = Math.toDegrees(angular) * BBOX_SAFETY;
        final var cosLat = Math.cos(Math.toRadians(center.lat()));
        final var sinAngular = Math.sin(angular);
        final double lngDelta;
        if (angular >= Math.PI / 2 || cosLat < 1e-9 || sinAngular >= cosLat) {
            lngDelta = 180;
        } else {
            lngDelta = Math.toDegrees(Math.asin(sinAngular / cosLat)) * BBOX_SAFETY;
        }
        return new BoundingBox(clampLat(center.lat() - latDelta), clampLng(center.lng() - lngDelta),
                clampLat(center.lat() + latDelta), clampLng(center.lng() + lngDelta));
    }

    public static BoundingBox boundingBoxOf(List<GeoPoint> points) {
        var minLat = Double.POSITIVE_INFINITY;
        var minLng = Double.POSITIVE_INFINITY;
        var maxLat = Double.NEGATIVE_INFINITY;
        var maxLng = Double.NEGATIVE_INFINITY;
        for (var point : points) {
            minLat = Math.min(minLat, point.lat());
            minLng = Math.min(minLng, point.lng());
            maxLat = Math.max(maxLat, point.lat());
            maxLng = Math.max(maxLng, point.lng());
        }
        return new BoundingBox(minLat, minLng, maxLat, maxLng);
    }

    public static Set<String> coveringGeohashPrefixes(BoundingBox bbox) {
        var best = cellsForPrecision(bbox, 1);
        for (var precision = 2; precision <= Globals.GEO_HASH_MAX_PRECISION; precision++) {
            final var cells = cellsForPrecision(bbox, precision);
            if (cells.size() > Globals.GEO_HASH_MAX_COVERING_CELLS) {
                break;
            }
            best = cells;
        }
        return best;
    }

    private static Set<String> cellsForPrecision(BoundingBox bbox, int precision) {
        final var latBits = 5 * precision / 2;
        final var lngBits = (5 * precision + 1) / 2;
        final var latCell = 180.0 / Math.pow(2, latBits);
        final var lngCell = 360.0 / Math.pow(2, lngBits);
        final var cells = new HashSet<String>();
        // Half-cell steps continuing one cell past each max edge: that is what guarantees every cell
        // overlapping the box is sampled, and a missed cell drops candidates from the range scan.
        for (var lat = bbox.minLat(); lat <= bbox.maxLat() + latCell; lat += latCell / 2) {
            for (var lng = bbox.minLng(); lng <= bbox.maxLng() + lngCell; lng += lngCell / 2) {
                cells.add(geoHash(clampLat(lat), clampLng(lng), precision));
            }
        }
        return cells;
    }

    private static double clampLat(double lat) {
        return Math.clamp(lat, -90, 90);
    }

    private static double clampLng(double lng) {
        return Math.clamp(lng, -180, 180);
    }
}
