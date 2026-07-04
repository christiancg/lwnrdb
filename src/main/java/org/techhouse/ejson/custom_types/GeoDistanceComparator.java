package org.techhouse.ejson.custom_types;

// The comparators the geo "distance" custom operator supports: each compares the haversine distance
// between the stored point and the target point against the threshold. The constant names match the
// comparator values accepted on the wire (a subset of the aggregation field-operator names), so
// GeoDistanceComparator.valueOf parses a wire comparator directly. Defined in the standalone ejson
// layer so JsonGeo does not depend on the aggregation (ops) packages.
public enum GeoDistanceComparator {
    EQUALS, GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS
}
