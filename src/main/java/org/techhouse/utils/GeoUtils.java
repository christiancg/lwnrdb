package org.techhouse.utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.config.Globals;

// Self-contained geo math (no external libraries): geohash encoding, haversine distance,
// point-in-polygon testing and the bounding-box helpers used by the spatial index acceleration.
public final class GeoUtils {
    private GeoUtils() {
    }

    // Standard geohash base-32 alphabet (excludes a, i, l, o).
    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    // Approximate metres per degree of latitude; used only to size a query bounding box (candidates
    // are always re-tested with the exact haversine distance, so the approximation is safe).
    private static final double METERS_PER_DEGREE_LAT = 111320.0;

    // An axis-aligned latitude/longitude rectangle used to pre-filter candidate points.
    public record BoundingBox(double minLat, double minLng, double maxLat, double maxLng) {
        public boolean contains(GeoPoint point) {
            return point.lat() >= minLat && point.lat() <= maxLat && point.lng() >= minLng && point.lng() <= maxLng;
        }
    }

    // Encodes a coordinate to a geohash string of the given precision (number of base-32 chars).
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

    // Great-circle distance between two points in metres.
    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        final var lat1 = Math.toRadians(a.lat());
        final var lat2 = Math.toRadians(b.lat());
        final var dLat = Math.toRadians(b.lat() - a.lat());
        final var dLng = Math.toRadians(b.lng() - a.lng());
        final var h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * Globals.EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    // Ray-casting point-in-polygon test (the polygon is an ordered list of vertices; it is treated as
    // implicitly closed, so the last and first vertices are joined). Longitude is x, latitude is y.
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

    // Bounding box that comfortably contains every point within radiusMeters of the centre. Longitude
    // spans widen towards the poles; near a pole (cos ~ 0) the box is clamped to the full longitude
    // range so no candidate is missed.
    public static BoundingBox boundingBoxForRadius(GeoPoint center, double radiusMeters) {
        final var latDelta = radiusMeters / METERS_PER_DEGREE_LAT;
        final var cosLat = Math.cos(Math.toRadians(center.lat()));
        final double lngDelta;
        if (cosLat < 1e-9) {
            lngDelta = 180;
        } else {
            lngDelta = radiusMeters / (METERS_PER_DEGREE_LAT * cosLat);
        }
        return new BoundingBox(clampLat(center.lat() - latDelta), clampLng(center.lng() - lngDelta),
                clampLat(center.lat() + latDelta), clampLng(center.lng() + lngDelta));
    }

    // Smallest axis-aligned box containing every vertex of the polygon.
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

    // The set of geohash prefixes that fully tile the bounding box. Picks the finest precision whose
    // covering stays within GEO_HASH_MAX_COVERING_CELLS (finer precision = smaller cells = more
    // pruning, but more cells), so the caller can turn each prefix into a contiguous range in a
    // geohash-sorted index. Guaranteed to cover the box: the grid is walked in half-cell steps and
    // the four corners are always included.
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
        // Walk the box in half-cell steps and continue one full cell past each max edge, so the band
        // between the last in-range sample and the edge is still sampled at every longitude/latitude.
        // Stepping by half a cell guarantees every cell overlapping the box contains a sample, so the
        // covering is complete (a missed cell would drop candidates from the index range scan).
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
