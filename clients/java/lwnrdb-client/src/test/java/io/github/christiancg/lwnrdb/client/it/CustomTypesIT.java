package io.github.christiancg.lwnrdb.client.it;

import static io.github.christiancg.lwnrdb.client.it.IndexComparison.ids;
import static io.github.christiancg.lwnrdb.client.it.IndexComparison.sameWithAndWithoutIndex;
import static io.github.christiancg.lwnrdb.client.it.IndexComparison.sortedIds;
import static io.github.christiancg.lwnrdb.client.query.Filters.distance;
import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static io.github.christiancg.lwnrdb.client.query.Filters.nearest;
import static io.github.christiancg.lwnrdb.client.query.Filters.within;
import static io.github.christiancg.lwnrdb.client.query.MapOps.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.query.MidOps;
import io.github.christiancg.lwnrdb.client.query.Order;
import io.github.christiancg.lwnrdb.client.values.CustomType;
import io.github.christiancg.lwnrdb.client.values.GeoDistanceComparator;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CustomTypesIT extends ServerIT {
    private static final GeoPoint NEW_YORK = GeoPoint.of(40.7128, -74.0060);
    private static final GeoPoint NEWARK = GeoPoint.of(40.7357, -74.1724);
    private static final GeoPoint LIMA = GeoPoint.of(-12.0464, -77.0428);
    private Collection<Document> places;

    public record Place(String id, LocalDateTime openedAt, LocalTime opensAt, GeoPoint location, Vector embedding) {
    }

    @Override
    protected Map<String, String> config() {
        return Map.of("maxVectorDimensions", "8");
    }

    @BeforeAll
    public void seed() {
        final var database = client.database("custom_it");
        database.create();
        places = database.collection("places");
        places.create();
        database.collection("typed").create();
        places.saveAll(List.of(
                Document.of("_id", "ny", "openedAt", LocalDateTime.of(2024, 3, 1, 9, 0), "opensAt", LocalTime.of(9, 0),
                        "location", NEW_YORK, "embedding", Vector.of(1, 0, 0)),
                Document.of("_id", "nwk", "openedAt", LocalDateTime.of(2023, 12, 31, 23, 59, 59), "opensAt",
                        LocalTime.of(10, 30), "location", NEWARK, "embedding", Vector.of(0.9, 0.1, 0)),
                Document.of("_id", "lima", "openedAt", LocalDateTime.of(2024, 1, 1, 10, 0), "opensAt",
                        LocalTime.of(8, 15), "location", LIMA, "embedding", Vector.of(0, 1, 0), "stops",
                        List.of(GeoPoint.of(1, 2), GeoPoint.of(3, 4)))));
    }

    @Test
    public void test_every_custom_type_round_trips_through_documents_and_records() {
        final var lima = places.findById("lima").orElseThrow();
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), lima.getDateTime("openedAt").orElseThrow());
        assertEquals(LocalTime.of(8, 15), lima.getTime("opensAt").orElseThrow());
        assertEquals(LIMA, lima.getGeo("location").orElseThrow());
        assertEquals(Vector.of(0, 1, 0), lima.getVector("embedding").orElseThrow());
        assertEquals(2, lima.getList("stops").orElseThrow().size());
        final var typed = client.database("custom_it").collection("typed", Place.class);
        final var place = new Place("p1", LocalDateTime.of(2024, 5, 5, 5, 5, 5), LocalTime.of(6, 7), NEW_YORK,
                Vector.of(0.25, 0.5));
        typed.save(place);
        assertEquals(place.embedding(), typed.findById("p1").orElseThrow().embedding());
        assertEquals(place, typed.findById("p1").orElseThrow());
    }

    @Test
    public void test_datetime_and_time_compare_and_sort_chronologically() {
        assertEquals(List.of("lima", "ny"), sameWithAndWithoutIndex(places, "openedAt", () -> sortedIds(
                places.query().filter(field("openedAt").gte(LocalDateTime.of(2024, 1, 1, 10, 0, 0))).toList())));
        assertEquals(List.of("lima"), sameWithAndWithoutIndex(places, "openedAt", () -> ids(
                places.query().filter(field("openedAt").eq(LocalDateTime.of(2024, 1, 1, 10, 0, 0))).toList())));
        assertEquals(List.of("nwk", "lima", "ny"), sameWithAndWithoutIndex(places, "openedAt",
                () -> ids(places.query().sort("openedAt", Order.ASC).toList())));
        assertEquals(List.of("nwk"), ids(places.query().filter(field("opensAt").gt(LocalTime.of(9, 0))).toList()));
    }

    @Test
    public void test_geo_distance_within_and_in() {
        final var near = sameWithAndWithoutIndex(places, "location", () -> sortedIds(places.query()
                .filter(distance("location", NEW_YORK, GeoDistanceComparator.SMALLER_THAN, 20_000)).toList()));
        assertEquals(List.of("nwk", "ny"), near);
        assertEquals(List.of("lima"), sortedIds(places.query()
                .filter(distance("location", NEW_YORK, GeoDistanceComparator.GREATER_THAN, 20_000)).toList()));
        assertEquals(3, places.query()
                .filter(distance("location", NEW_YORK, GeoDistanceComparator.GREATER_THAN_EQUALS, 0)).count());
        assertEquals(1, places.query()
                .filter(distance("location", NEW_YORK, GeoDistanceComparator.SMALLER_THAN_EQUALS, 0)).count());
        assertEquals(1, places.query().filter(distance("location", NEW_YORK, GeoDistanceComparator.EQUALS, 0)).count());
        final var square = within("location", GeoPoint.of(40.6, -74.1), GeoPoint.of(40.8, -74.1),
                GeoPoint.of(40.8, -73.9), GeoPoint.of(40.6, -73.9));
        assertEquals(List.of("ny"), ids(places.query().filter(square).toList()));
        assertEquals(List.of("lima", "ny"), sameWithAndWithoutIndex(places, "location",
                () -> sortedIds(places.query().filter(field("location").in(NEW_YORK, LIMA)).toList())));
    }

    @Test
    public void test_vector_nearest_and_equality() {
        final var exact = places.query().filter(nearest("embedding", Vector.of(1, 0, 0), 2).exact()).toList();
        assertEquals(List.of("ny", "nwk"), ids(exact));
        final var approximate = places.query().filter(nearest("embedding", Vector.of(1, 0, 0), 2)).toList();
        assertTrue(approximate.size() <= 2);
        assertEquals(List.of("lima"), ids(places.query().filter(field("embedding").eq(Vector.of(0, 1, 0))).toList()));
    }

    @Test
    public void test_casts_to_custom_types_and_refusals() {
        places.save(Document.of("_id", "raw", "text", "2024-01-01T10:00", "bad", "x"));
        final var cast = places.query().filter(field("_id").eq("raw"))
                .map(set("typed", MidOps.cast("text", CustomType.DATETIME)),
                        set("broken", MidOps.cast("bad", CustomType.GEO)))
                .toList().get(0);
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), cast.getDateTime("typed").orElseThrow());
        assertNull(cast.get("broken").orElse(null));
        places.delete("raw");
        final var tooLong = Vector.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        final var error = assertThrows(LwnrdbException.class,
                () -> places.save(Document.of("_id", "v", "embedding", tooLong)));
        assertEquals("400-43", error.rawCode().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> places.save(Document.of("note", "#geo(1,2)")));
    }
}
