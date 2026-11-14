package io.github.christiancg.lwnrdb.spring.data.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.query.Filters;
import io.github.christiancg.lwnrdb.spring.data.Stack;
import io.github.christiancg.lwnrdb.spring.data.model.Plain;
import io.github.christiancg.lwnrdb.spring.data.model.Widget;
import io.github.christiancg.lwnrdb.spring.data.model.WidgetRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class LwnrdbTemplateTest {
    private static final String ROW = "{\"_id\":\"w1\",\"name\":\"bolt\",\"size\":2}";
    private static final String DELETED_FOUR = "{\"type\":\"DELETE\",\"status\":\"OK\",\"deletedCount\":4}";
    private static final String TOO_MANY = "{\"type\":\"DELETE\",\"status\":\"ERROR\",\"errorCode\":\"400-44\"}";

    private Stack stack;
    private LwnrdbTemplate template;

    @BeforeEach
    public void start() throws Exception {
        stack = new Stack().results("[" + ROW + "]");
        template = stack.template;
    }

    @AfterEach
    public void stop() throws Exception {
        stack.close();
    }

    @Test
    public void test_save_generates_ids_and_writes_the_wire_shape() throws Exception {
        final var saved = template.save(Widget.of(null, "bolt", 2));
        final var request = stack.next("SAVE");
        assertEquals(saved.code(), request.get("object").get("_id").asString());
        assertEquals("shop", request.get("databaseName").asString());
        assertEquals("widgets", request.get("collectionName").asString());
        final var all = template.saveAll(List.of(Widget.of("a", "x", 1), Widget.of(null, "y", 2)));
        assertEquals(2, stack.next("BULK_SAVE").get("objects").size());
        assertEquals("a", all.get(0).code());
        assertTrue(template.saveAll(List.of()).isEmpty());
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> template.saveAll(List.of(Widget.of("a", "x", 1), new Plain())));
    }

    @Test
    public void test_reads_and_counts() throws Exception {
        stack.replies.reply("FIND_BY_ID", "{\"type\":\"FIND_BY_ID\",\"status\":\"OK\",\"object\":" + ROW + "}");
        assertEquals("bolt", template.findById("w1", Widget.class).orElseThrow().name());
        assertTrue(template.existsById("w1", Widget.class));
        assertEquals(1, template.findAll(Widget.class).size());
        assertEquals(1, template.findAll(Widget.class, Sort.by("size")).size());
        assertEquals("[]", stack.steps("AGGREGATE"));
        assertTrue(stack.steps("AGGREGATE").contains("\"SORT\""));
        assertEquals(1, template.findAllById(List.of("w1"), Widget.class).size());
        assertTrue(stack.steps("AGGREGATE").contains("\"IN\""));
        assertTrue(template.findAllById(List.of(), Widget.class).isEmpty());
        assertEquals("bolt", template.query(Widget.class).toList().get(0).name());
        stack.count(5);
        assertEquals(5, template.count(Widget.class));
        assertEquals(5, template.count(Widget.class, Filters.field("name").eq("bolt")));
        stack.results("[" + ROW + "]");
        assertTrue(template.exists(Widget.class, null));
        assertEquals("shop", template.getMappingContext().defaultDatabase());
        assertSame(stack.client, template.getClient());
        assertEquals("ok", template.execute(client -> "ok"));
    }

    @Test
    public void test_deletes() throws Exception {
        template.deleteById("w1", Widget.class);
        assertEquals("w1", stack.next("DELETE").get("_id").asString());
        stack.replies.reply("DELETE", "{\"type\":\"DELETE\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-2\"}");
        template.deleteById("gone", Widget.class);
        stack.replies.reply("DELETE", "{\"type\":\"DELETE\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-11\"}");
        assertThrows(DataRetrievalFailureException.class, () -> template.deleteById("w1", Widget.class));
        stack.replies.forget("DELETE");
        template.delete(Widget.of("w9", "x", 1));
        assertThrows(InvalidDataAccessApiUsageException.class, () -> template.delete(Widget.of(null, "x", 1)));
        template.deleteAllById(List.of("a", "b"), Widget.class);
        stack.replies.reply("DELETE", DELETED_FOUR);
        assertEquals(4, template.deleteAll(Widget.class));
    }

    @Test
    public void test_delete_all_is_one_conditional_delete_of_every_id() throws Exception {
        stack.replies.reply("DELETE", DELETED_FOUR);

        assertEquals(4, template.deleteAll(Widget.class));

        final var condition = stack.next("DELETE").get("condition");
        assertEquals("NOT_EQUALS", condition.get("fieldOperatorType").asString());
        assertEquals("_id", condition.get("field").asString());
        assertEquals("", condition.get("value").asString());
    }

    @Test
    public void test_delete_where_sends_the_filter_and_translates_a_refusal() throws Exception {
        stack.replies.reply("DELETE", DELETED_FOUR);
        assertEquals(4, template.deleteWhere(Widget.class, Filters.field("name").eq("bolt")));
        assertEquals("name", stack.next("DELETE").get("condition").get("field").asString());
        assertEquals(4, template.deleteWhere(Widget.class, null));
        assertEquals("_id", stack.next("DELETE").get("condition").get("field").asString());
        stack.replies.reply("DELETE", TOO_MANY);
        assertThrows(DataAccessException.class,
                () -> template.deleteWhere(Widget.class, Filters.field("name").eq("bolt")));
    }

    @Test
    public void test_a_capped_conditional_delete_falls_back_to_reading_and_deleting_one_at_a_time() throws Exception {
        stack.replies.replyOnce("DELETE", TOO_MANY);

        assertEquals(1, template.deleteMatching(Widget.class, LwnrdbQuerySpec.of(Filters.field("name").eq("bolt"))));

        assertEquals("name", stack.next("DELETE").get("condition").get("field").asString());
        assertEquals("w1", stack.next("DELETE").get("_id").asString());
    }

    @Test
    public void test_delete_matching_with_a_sort_reads_first_and_other_refusals_are_translated() throws Exception {
        assertEquals(1, template.deleteMatching(Widget.class,
                LwnrdbQuerySpec.of(Filters.field("name").eq("bolt")).sorted(Sort.by("size"))));
        assertEquals("w1", stack.next("DELETE").get("_id").asString());
        stack.replies.reply("DELETE", "{\"type\":\"DELETE\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-11\"}");
        assertThrows(DataRetrievalFailureException.class, () -> template.deleteAll(Widget.class));
    }

    @Test
    public void test_the_repository_delegates_crud_to_the_template() {
        final var widgets = stack.repository(WidgetRepository.class);
        widgets.save(Widget.of("w1", "a", 1));
        widgets.saveAll(List.of(Widget.of("w2", "b", 2)));
        assertEquals(1, widgets.findAll().size());
        assertEquals(1, widgets.findAll(Sort.by("name")).size());
        assertEquals(1, widgets.findAllById(List.of("w1")).size());
        assertEquals(1, widgets.findAll(Pageable.unpaged()).getContent().size());
        final var page = widgets.findAll(PageRequest.of(0, 1, Sort.by("size")));
        assertEquals(1, page.getContent().size());
        widgets.deleteById("w1");
        widgets.delete(Widget.of("w1", "a", 1));
        widgets.deleteAllById(List.of("w1"));
        widgets.deleteAll(List.of(Widget.of("w1", "a", 1)));
        widgets.deleteAll();
        stack.replies.reply("FIND_BY_ID", "{\"type\":\"FIND_BY_ID\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-2\"}");
        assertTrue(widgets.findById("none").isEmpty());
        assertFalse(widgets.existsById("none"));
        stack.count(9);
        assertEquals(9, widgets.count());
    }
}
