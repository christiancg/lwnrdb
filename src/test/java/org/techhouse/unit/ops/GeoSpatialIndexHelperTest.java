package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.GeoSpatialIndexHelper;
import org.techhouse.ops.req.agg.operators.CustomOperator;

// Unit coverage of the branches GeoSpatialIndexHelper resolves without touching the cache: an operator
// whose matching set a bounding box cannot prune returns null so the caller falls back to a full scan.
public class GeoSpatialIndexHelperTest {
    @Test
    public void test_distance_greater_than_is_not_index_accelerable() throws Exception {
        final var target = new JsonGeo("#geo(40.0,-74.0)");
        final var args = new JsonObject();
        args.add("value", target);
        args.add("comparator", new JsonString("GREATER_THAN"));
        args.add("distance", new JsonNumber(1000));
        final var op = new CustomOperator("distance", "location", target, args);

        assertNull(GeoSpatialIndexHelper.candidateIds(op, "db", "coll"));
    }

    @Test
    public void test_unknown_custom_operator_is_not_index_accelerable() throws Exception {
        final var op = new CustomOperator("mystery", "location", null, new JsonObject());

        assertNull(GeoSpatialIndexHelper.candidateIds(op, "db", "coll"));
    }
}
