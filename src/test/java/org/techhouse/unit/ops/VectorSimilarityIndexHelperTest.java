package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.VectorSimilarityIndexHelper;
import org.techhouse.ops.req.agg.operators.CustomOperator;

// Unit coverage of the branches VectorSimilarityIndexHelper resolves without touching the cache: an
// operator that is not the nearest ranking operator, and an "exact" query, both return null so the
// caller falls back to a full scan.
public class VectorSimilarityIndexHelperTest {
    @Test
    public void test_non_nearest_operator_is_not_index_accelerable() throws Exception {
        final var op = new CustomOperator("mystery", "embedding", null, new JsonObject());

        assertNull(VectorSimilarityIndexHelper.candidateIds(op, "db", "coll"));
    }

    @Test
    public void test_exact_query_is_not_index_accelerable() throws Exception {
        final var query = new JsonVector("#vector(1.0,0.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(5));
        args.add("exact", new JsonBoolean(true));
        final var op = new CustomOperator("nearest", "embedding", query, args);

        assertNull(VectorSimilarityIndexHelper.candidateIds(op, "db", "coll"));
    }
}
