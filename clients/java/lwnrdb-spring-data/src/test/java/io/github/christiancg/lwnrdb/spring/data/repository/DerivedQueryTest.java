package io.github.christiancg.lwnrdb.spring.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.spring.data.Stack;
import io.github.christiancg.lwnrdb.spring.data.model.InvalidRepositories;
import io.github.christiancg.lwnrdb.spring.data.model.WidgetRepository;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class DerivedQueryTest {
    private static final String ROW = "{\"_id\":\"w1\",\"name\":\"bolt\",\"size\":2}";

    private Stack stack;
    private WidgetRepository widgets;

    @BeforeEach
    public void start() throws Exception {
        stack = new Stack().results("[" + ROW + "]");
        widgets = stack.repository(WidgetRepository.class);
    }

    @AfterEach
    public void stop() throws Exception {
        stack.close();
    }

    private String filterOf(Consumer<WidgetRepository> call) throws InterruptedException {
        call.accept(widgets);
        return stack.steps("AGGREGATE");
    }

    private static String field(String operator, String field, String value) {
        return "{\"fieldOperatorType\":\"" + operator + "\",\"field\":\"" + field + "\",\"value\":" + value + "}";
    }

    @Test
    public void test_every_comparison_keyword_becomes_its_filter() throws Exception {
        assertTrue(filterOf(r -> r.findByName("bolt")).contains(field("EQUALS", "name", "\"bolt\"")));
        assertTrue(filterOf(r -> r.findByNameNot("bolt")).contains(field("NOT_EQUALS", "name", "\"bolt\"")));
        assertTrue(filterOf(WidgetRepository::findByNameIsNull).contains(field("EQUALS", "name", "null")));
        assertTrue(filterOf(WidgetRepository::findByNameIsNotNull).contains(field("NOT_EQUALS", "name", "null")));
        assertTrue(filterOf(r -> r.findBySizeGreaterThan(2)).contains(field("GREATER_THAN", "size", "2.0")));
        assertTrue(
                filterOf(r -> r.findBySizeGreaterThanEqual(2)).contains(field("GREATER_THAN_EQUALS", "size", "2.0")));
        assertTrue(filterOf(r -> r.findBySizeLessThan(2)).contains(field("SMALLER_THAN", "size", "2.0")));
        assertTrue(filterOf(r -> r.findBySizeLessThanEqual(2)).contains(field("SMALLER_THAN_EQUALS", "size", "2.0")));
        assertTrue(filterOf(r -> r.findBySizeAfter(2)).contains(field("GREATER_THAN", "size", "2.0")));
        assertTrue(filterOf(r -> r.findBySizeBefore(2)).contains(field("SMALLER_THAN", "size", "2.0")));
        final var between = filterOf(r -> r.findBySizeBetween(1, 3));
        assertTrue(between.contains("\"conjunctionType\":\"AND\""), between);
        assertTrue(between.contains(field("GREATER_THAN_EQUALS", "size", "1.0")), between);
        assertTrue(filterOf(r -> r.findByNameIn(List.of("a", "b"))).contains(field("IN", "name", "[\"a\",\"b\"]")));
        assertTrue(filterOf(r -> r.findByNameIn(new String[]{"a"})).contains(field("IN", "name", "[\"a\"]")));
        assertTrue(filterOf(r -> r.findByNameNotIn(List.of("a"))).contains(field("NOT_IN", "name", "[\"a\"]")));
        assertTrue(filterOf(r -> r.findByNameContaining("ol")).contains(field("CONTAINS", "name", "\"ol\"")));
        assertTrue(filterOf(WidgetRepository::findByTitleTrue).contains(field("EQUALS", "label", "true")));
        assertTrue(filterOf(WidgetRepository::findByTitleFalse).contains(field("EQUALS", "label", "false")));
        assertTrue(filterOf(r -> r.findByAddressCity("Lima")).contains(field("EQUALS", "address.city", "\"Lima\"")));
        assertTrue(filterOf(r -> r.findByNameIgnoreCase("BOLT")).contains(field("EQUALS", "name", "\"BOLT\"")));
    }

    @Test
    public void test_and_or_nesting_and_geo_within() throws Exception {
        final var nested = filterOf(r -> r.findByNameAndSizeGreaterThanOrTitle("a", 1, "t"));
        assertTrue(nested.contains("\"conjunctionType\":\"OR\""), nested);
        assertTrue(nested.contains("\"conjunctionType\":\"AND\""), nested);
        final var within = filterOf(
                r -> r.findByAddressCityWithin(List.of(GeoPoint.of(0, 0), GeoPoint.of(1, 0), GeoPoint.of(0, 1))));
        assertTrue(within.contains("\"customOperatorName\":\"within\""), within);
        assertThrows(InvalidDataAccessApiUsageException.class, () -> widgets.findByAddressCityWithin(List.of()));
    }

    @Test
    public void test_limits_sorts_and_pages_become_pipeline_steps() throws Exception {
        final var top = filterOf(r -> r.findTop3ByName("a"));
        assertTrue(top.contains("{\"type\":\"LIMIT\",\"limit\":3}"), top);
        assertTrue(filterOf(r -> r.findFirstByNameOrderBySizeDesc("a"))
                .contains("{\"type\":\"SORT\",\"fieldName\":\"size\",\"ascending\":false}"));
        final var sorted = filterOf(r -> r.findByName("a", Sort.by("title")));
        assertTrue(sorted.contains("\"fieldName\":\"label\""), sorted);
        assertTrue(filterOf(r -> r.findByName("a", Limit.of(4))).contains("{\"type\":\"LIMIT\",\"limit\":4}"));
        final var paged = filterOf(r -> r.findByName("a", PageRequest.of(2, 5)));
        assertTrue(paged.contains("{\"type\":\"SKIP\",\"skip\":10}"), paged);
        assertTrue(paged.contains("{\"type\":\"LIMIT\",\"limit\":5}"), paged);
        assertTrue(filterOf(r -> r.findByName("a", Pageable.unpaged())).contains("\"FILTER\""));
        assertThrows(InvalidDataAccessApiUsageException.class, () -> widgets.findByName("a", Sort.by("size", "name")));
    }

    @Test
    public void test_result_shapes() {
        assertEquals("w1", widgets.findOneByName("bolt").orElseThrow().code());
        assertEquals(1, widgets.findByName("bolt").size());
        try (var stream = widgets.streamByName("bolt")) {
            assertEquals(1, stream.count());
        }
        stack.results("[" + ROW + "," + ROW + "]");
        assertThrows(IncorrectResultSizeDataAccessException.class, () -> widgets.findOneByName("bolt"));
        final var slice = widgets.findSliceByName("bolt", PageRequest.of(0, 1));
        assertTrue(slice.hasNext());
        assertEquals(1, slice.getContent().size());
        assertFalse(widgets.findSliceByName("bolt", Pageable.unpaged()).hasNext());
        stack.results("[]");
        assertTrue(widgets.findOneByName("none").isEmpty());
        assertNull(widgets.findFirstByNameOrderBySizeDesc("none"));
        assertFalse(widgets.findSliceByName("bolt", PageRequest.of(0, 1)).hasNext());
    }

    @Test
    public void test_pages_count_their_total() {
        stack.results("[" + ROW + "]");
        final var page = widgets.findPageByName("bolt", PageRequest.of(0, 1));
        assertEquals(1, page.getContent().size());
        stack.results("[{\"_id\":\"w1\",\"name\":\"bolt\",\"size\":2,\"count\":7}]");
        assertEquals(7, widgets.findPageByName("bolt", PageRequest.of(0, 1)).getTotalElements());
    }

    @Test
    public void test_count_exists_and_delete_projections() throws Exception {
        stack.count(3);
        assertEquals(3, widgets.countByName("bolt"));
        assertTrue(stack.steps("AGGREGATE").contains("\"COUNT\""));
        stack.results("[" + ROW + "]");
        assertTrue(widgets.existsByName("bolt"));
        assertEquals(1, widgets.deleteByName("bolt"));
        assertEquals("w1", stack.next("DELETE").get("_id").asString());
        assertEquals(1, widgets.removeByName("bolt").size());
        stack.results("[]");
        assertFalse(widgets.existsByName("none"));
    }

    @Test
    public void test_unsupported_methods_fail_when_the_repository_is_created() {
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> stack.repository(InvalidRepositories.LikeRepository.class));
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> stack.repository(InvalidRepositories.TwoOrdersRepository.class));
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> stack.repository(InvalidRepositories.IdIgnoreCaseRepository.class));
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> stack.repository(InvalidRepositories.TransientRepository.class));
        assertThrows(RuntimeException.class, () -> stack.repository(InvalidRepositories.NumericIdRepository.class));
    }
}
