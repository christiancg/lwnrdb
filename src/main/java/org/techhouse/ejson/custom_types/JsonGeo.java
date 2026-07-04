package org.techhouse.ejson.custom_types;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;
import org.techhouse.utils.GeoPoint;
import org.techhouse.utils.GeoUtils;

// A geographic point stored as "#geo(lat,lng)". Beyond the standard equality/ordering used by every
// custom type, it exposes two custom filter operators: "distance" (compare the haversine distance to
// a target point against a threshold via a comparator) and "within" (point-in-polygon over an array
// of points forming an enclosed shape).
public class JsonGeo extends JsonCustom<GeoPoint> {
    // The custom type name (the "#geo(...)" prefix) and the names of the custom filter operators this
    // type exposes. They live here — with the geo type itself — rather than in the global config.
    public static final String CUSTOM_TYPE_NAME = "geo";
    public static final String OPERATOR_DISTANCE = "distance";
    public static final String OPERATOR_WITHIN = "within";

    // Number of geohash characters used for ordering; high enough that distinct coordinates virtually
    // always differ (ties are broken by lat/lng anyway, so equality stays exact).
    private static final int GEO_HASH_ORDER_PRECISION = 12;

    public JsonGeo(GeoPoint customValue) {
        // Build the "#geo(lat,lng)" wire value explicitly (rather than relying on GeoPoint.toString),
        // so GeoPoint stays a plain data record; the string constructor parses it back into customValue.
        super("#" + CUSTOM_TYPE_NAME + "(" + customValue.lat() + "," + customValue.lng() + ")");
    }

    public JsonGeo(String strValue) {
        super(strValue);
    }

    public JsonGeo() {
        super();
    }

    @Override
    public String getCustomTypeName() {
        return CUSTOM_TYPE_NAME;
    }

    @Override
    protected GeoPoint parse() {
        try {
            final var parts = stringDataValue().split(",");
            if (parts.length != 2) {
                throw new WrongFormatCustomTypeException(getClass().getName());
            }
            final var lat = Double.parseDouble(parts[0].trim());
            final var lng = Double.parseDouble(parts[1].trim());
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                throw new WrongFormatCustomTypeException(getClass().getName());
            }
            return new GeoPoint(lat, lng);
        } catch (WrongFormatCustomTypeException e) {
            throw e;
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    // Orders by geohash so the on-disk/in-memory index is spatially clustered (enabling the geohash
    // range scan), with lat then lng as tie-breakers so compare(...) == 0 iff the points are equal.
    @Override
    public Integer compare(GeoPoint another) {
        final var byHash = geoHash()
                .compareTo(GeoUtils.geoHash(another.lat(), another.lng(), GEO_HASH_ORDER_PRECISION));
        if (byHash != 0) {
            return byHash;
        }
        final var byLat = Double.compare(customValue.lat(), another.lat());
        if (byLat != 0) {
            return byLat;
        }
        return Double.compare(customValue.lng(), another.lng());
    }

    public GeoPoint point() {
        return customValue;
    }

    public String geoHash() {
        return GeoUtils.geoHash(customValue.lat(), customValue.lng(), GEO_HASH_ORDER_PRECISION);
    }

    @Override
    public Set<String> customOperatorNames() {
        return Set.of(OPERATOR_DISTANCE, OPERATOR_WITHIN);
    }

    @Override
    public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
        return switch (operatorName) {
            case OPERATOR_DISTANCE -> applyDistance(args);
            case OPERATOR_WITHIN -> applyWithin(args);
            default -> throw new UnsupportedOperationException(
                    getCustomTypeName() + " does not support custom operator " + operatorName);
        };
    }

    private boolean applyDistance(Map<String, JsonBaseElement> args) {
        final var target = toGeoPoint(args.get("value"));
        final var comparator = GeoDistanceComparator.valueOf(args.get("comparator").asJsonString().getValue());
        final var threshold = args.get("distance").asJsonNumber().getValue().doubleValue();
        final var distance = GeoUtils.haversineMeters(customValue, target);
        return switch (comparator) {
            case SMALLER_THAN -> distance < threshold;
            case SMALLER_THAN_EQUALS -> distance <= threshold;
            case GREATER_THAN -> distance > threshold;
            case GREATER_THAN_EQUALS -> distance >= threshold;
            case EQUALS -> distance == threshold;
        };
    }

    private boolean applyWithin(Map<String, JsonBaseElement> args) {
        final var polygonArray = args.get("polygon").asJsonArray();
        final var polygon = new ArrayList<GeoPoint>();
        for (var vertex : polygonArray.asList()) {
            polygon.add(toGeoPoint(vertex));
        }
        return GeoUtils.pointInPolygon(customValue, polygon);
    }

    // Accepts a geo argument that arrives either already parsed as a JsonGeo or as a "#geo(...)" string.
    private static GeoPoint toGeoPoint(JsonBaseElement element) {
        if (element instanceof JsonGeo geo) {
            return geo.point();
        }
        if (element != null && element.isJsonString()) {
            return new JsonGeo(element.asJsonString().getValue()).point();
        }
        throw new WrongFormatCustomTypeException(JsonGeo.class.getName());
    }
}
