package io.github.christiancg.lwnrdb.client.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.AnalyzeResult;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.DocumentCodec;
import io.github.christiancg.lwnrdb.client.internal.PipelineStep;
import io.github.christiancg.lwnrdb.client.internal.QueryTarget;
import io.github.christiancg.lwnrdb.client.values.CustomType;
import io.github.christiancg.lwnrdb.client.values.GeoDistanceComparator;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class QueryTest {
    private static final class Target implements QueryTarget<Document> {
        final List<List<PipelineStep>> runs = new ArrayList<>();

        @Override
        public DocumentCodec<Document> codec() {
            return DocumentCodec.identity();
        }

        @Override
        public List<Document> aggregate(List<PipelineStep> steps, boolean dirtyRead) {
            runs.add(steps);
            return List.of(Document.of("count", 7));
        }

        @Override
        public AnalyzeResult analyze(List<PipelineStep> steps, boolean dirtyRead) {
            return new AnalyzeResult(List.of(), Document.empty());
        }
    }

    @Test
    public void test_queries_are_immutable_and_branch_independently() {
        final var base = new Query<>(new Target()).filter(Filters.field("a").eq(1));
        final var limited = base.limit(5);
        final var sorted = base.sort("a", Order.ASC);
        assertEquals(1, base.steps().size());
        assertEquals(2, limited.steps().size());
        assertTrue(sorted.steps().get(1) instanceof PipelineStep.SortStep);
        assertFalse(base.isDirtyRead());
        assertTrue(base.dirtyRead().isDirtyRead());
    }

    @Test
    public void test_count_appends_exactly_one_count_step() {
        final var target = new Target();
        assertEquals(7, new Query<>(target).filter(Filters.field("a").eq(1)).count());
        assertEquals(2, target.runs.get(0).size());
        assertTrue(target.runs.get(0).get(1) instanceof PipelineStep.CountStep);
    }

    @Test
    public void test_invalid_steps_are_refused_before_sending() {
        final var query = new Query<>(new Target());
        assertThrows(IllegalArgumentException.class, () -> query.limit(0));
        assertThrows(IllegalArgumentException.class, () -> query.skip(-1));
        assertThrows(IllegalArgumentException.class, query::map);
        assertEquals(1, query.skip(0).steps().size());
    }

    @Test
    public void test_filter_and_operation_validation() {
        assertThrows(IllegalArgumentException.class, () -> Filters.within("f", GeoPoint.of(0, 0), GeoPoint.of(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Filters.nearest("f", Vector.of(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> Filters.distance("f", GeoPoint.of(0, 0), GeoDistanceComparator.EQUALS, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Filters.distance("f", GeoPoint.of(0, 0), GeoDistanceComparator.EQUALS, Double.NaN));
        assertThrows(IllegalArgumentException.class, Filters::and);
        assertThrows(IllegalArgumentException.class, MidOps::sum);
        assertThrows(IllegalArgumentException.class, () -> new CastOperation("f", CastType.JSON_CUSTOM, null));
        assertThrows(IllegalArgumentException.class, () -> new CastOperation("f", CastType.NUMBER, CustomType.GEO));
        assertTrue(Filters.within("f",
                List.of(GeoPoint.of(0, 0), GeoPoint.of(1, 0), GeoPoint.of(0, 1))) instanceof WithinFilter);
        assertTrue(Filters.field("f").in(List.of(1, 2)) instanceof FieldFilter);
        assertTrue(Filters.field("f").notIn(List.of(1)) instanceof FieldFilter);
    }

    @Test
    public void test_nearest_and_map_operations_expose_their_parts() {
        final var nearest = Filters.nearest("f", Vector.of(1, 2), 3);
        final var exact = nearest.exact();
        assertFalse(nearest.isExact());
        assertTrue(exact.isExact());
        assertEquals("f", exact.field());
        assertEquals(Vector.of(1, 2), exact.query());
        assertEquals(3, exact.k());
        assertNotEquals(nearest, exact);
        assertEquals(exact, nearest.exact());
        assertEquals(exact.hashCode(), nearest.exact().hashCode());
        assertTrue(exact.toString().contains("exact"));
        assertFalse(nearest.toString().contains("exact"));
        final var removal = MapOps.remove("x");
        assertTrue(removal.removes());
        assertTrue(removal.operation().isEmpty());
        assertTrue(removal.condition().isEmpty());
        assertEquals("x", removal.field());
        final var set = MapOps.set("y", MidOps.abs("z")).when(Filters.script("s"));
        assertFalse(set.removes());
        assertTrue(set.condition().isPresent());
        assertEquals("a", MidOps.literal("a").text());
    }
}
