package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.ClassPlan;
import org.techhouse.ejson.internal.FieldBinding;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;

public class ClassPlanTest {
    private final EJson eJson = new EJson();

    public abstract static class Fixture {
    }

    public static class Base {
        public String inherited;
    }

    public static class Child extends Base {
        public static final String STATIC_FIELD = "static";
        public String own;
        public transient String skipped;
    }

    public static final class Prefilled extends Fixture {
        private final String name;

        public Prefilled(String name) {
            this.name = "from-constructor:" + name;
        }

        public String name() {
            return name;
        }
    }

    public static final class Unconstructible extends Fixture {
        private final String name;

        private Unconstructible(int unrelated) {
            this.name = "never" + unrelated;
        }

        public String name() {
            return name;
        }
    }

    public static final class WithNoArgument extends Fixture {
        public final String tag;

        public WithNoArgument() {
            this.tag = "no-argument";
        }

        public WithNoArgument(String tag) {
            this.tag = "matching:" + tag;
        }
    }

    public static final class WithMatching extends Fixture {
        public final String tag;

        public WithMatching(String tag) {
            this.tag = "matching:" + tag;
        }
    }

    public static final class Allocated {
        public String tag;

        private Allocated(String first, String second) {
            this.tag = "constructed" + first + second;
        }
    }

    public record Row(String name, int count, List<String> tags) {
    }

    public enum Color {
        RED, GREEN
    }

    public static final class Inner {
        public String label;
    }

    public static final class Mixed {
        public Color color;
        public List<String> names;
        public Integer boxed;
        public long primitive;
        public Inner nested;
    }

    public static final class Defaults {
        public String value = "default";
    }

    public static final class WithMap {
        public Map<String, String> map = Map.of("k", "v");
        public String after = "after";
    }

    @BeforeEach
    public void setUp() {
        new EJson();
    }

    private JsonObject parsed(String json) {
        return eJson.fromJson(json, JsonObject.class);
    }

    @Test
    public void test_bindings_follow_declared_then_superclass_order() {
        final var names = Arrays.stream(ClassPlan.of(Child.class).bindings()).map(FieldBinding::name).toList();

        final var read = eJson.fromJson("{\"own\":\"o\",\"inherited\":\"i\"}", Child.class);

        assertEquals(List.of("own", "inherited"), names);
        assertEquals("static", Child.STATIC_FIELD);
        assertEquals("o", read.own);
        assertEquals("i", read.inherited);
        final var written = new Child();
        written.inherited = "base";
        assertEquals("{\"own\":null,\"inherited\":\"base\"}", eJson.toJson(written));
        assertEquals("\"own\":", ClassPlan.of(Child.class).bindings()[0].nameToken());
    }

    @Test
    public void test_static_and_transient_fields_are_not_bound() {
        final var save = Arrays.stream(ClassPlan.of(SaveRequest.class).bindings()).map(FieldBinding::name).toList();
        final var request = Arrays.stream(ClassPlan.of(OperationRequest.class).bindings()).map(FieldBinding::name)
                .toList();

        assertFalse(save.contains("backgroundFollowOn"));
        assertFalse(request.contains("schemaCheck"));
        assertFalse(Arrays.stream(ClassPlan.of(Child.class).bindings()).anyMatch(b -> b.name().equals("skipped")));
        assertFalse(Arrays.stream(ClassPlan.of(Child.class).bindings()).anyMatch(b -> b.name().equals("STATIC_FIELD")));
    }

    @Test
    public void test_a_final_field_is_set_through_the_field_and_not_overwritten_when_a_constructor_set_it() {
        final var set = eJson.fromJson("{\"name\":\"direct\"}", Unconstructible.class);
        final var kept = eJson.fromJson("{\"name\":\"x\"}", Prefilled.class);

        assertEquals("direct", set.name());
        assertEquals("from-constructor:x", kept.name());
    }

    @Test
    public void test_instantiator_prefers_no_arg_then_final_matching_constructor_then_allocator() {
        final var noArgument = eJson.fromJson("{\"tag\":\"x\"}", WithNoArgument.class);
        final var matching = eJson.fromJson("{\"tag\":\"x\"}", WithMatching.class);
        final var matchingWithoutValue = eJson.fromJson("{}", WithMatching.class);
        final var allocated = eJson.fromJson("{\"tag\":\"x\"}", Allocated.class);

        assertEquals("no-argument", new WithNoArgument().tag);
        assertEquals("matching:x", new WithNoArgument("x").tag);
        assertEquals("no-argument", noArgument.tag);
        assertEquals("matching:x", matching.tag);
        assertEquals("absent", Objects.toString(matchingWithoutValue.tag, "absent"));
        assertEquals("x", allocated.tag);
    }

    @Test
    public void test_records_use_their_canonical_constructor_with_defaults_for_missing_components() {
        final var row = eJson.fromJson("{\"name\":\"n\",\"tags\":[\"a\",\"b\"]}", Row.class);
        final var empty = eJson.fromJson("{}", Row.class);

        assertEquals(new Row("n", 0, List.of("a", "b")), row);
        assertEquals(new Row(null, 0, null), empty);
        assertTrue(ClassPlan.of(Row.class).isRecord());
    }

    @Test
    public void test_readers_convert_enum_list_boxed_number_primitive_and_nested_object() throws Exception {
        final var plan = ClassPlan.of(Mixed.class);

        final var mixed = eJson.fromJson("{\"color\":\"RED\",\"boxed\":3}", Mixed.class);
        assertEquals(Color.RED, mixed.color);
        assertEquals(3, mixed.boxed);
        assertEquals(Color.GREEN, plan.bindingNamed("color").reader().read(new JsonString("GREEN")));
        assertEquals(List.of("a", "b"),
                plan.bindingNamed("names").reader().read(parsed("{\"v\":[\"a\",\"b\"]}").get("v")));
        assertEquals(7, plan.bindingNamed("boxed").reader().read(new JsonNumber(7.9)));
        assertEquals(12L, plan.bindingNamed("primitive").reader().read(new JsonNumber(12.0)));
        final var nested = (Inner) plan.bindingNamed("nested").reader().read(parsed("{\"label\":\"l\"}"));
        final var expected = new Inner();
        expected.label = "l";
        assertEquals(expected.label, nested.label);
    }

    @Test
    public void test_unknown_keys_are_ignored_and_null_values_keep_the_default() {
        final var defaults = eJson.fromJson("{\"value\":null,\"unknown\":1}", Defaults.class);

        assertEquals("default", defaults.value);
    }

    @Test
    public void test_a_field_with_no_adapter_is_skipped_on_read_and_written_as_null() {
        final var withMap = eJson.fromJson("{\"map\":{\"x\":\"y\"},\"after\":\"read\"}", WithMap.class);

        assertEquals(Map.of("k", "v"), withMap.map);
        assertEquals("read", withMap.after);
        assertEquals("{\"map\":null,\"after\":\"after\"}", eJson.toJson(new WithMap()));
    }

    @Test
    public void test_the_plan_is_built_once_per_class() {
        assertSame(ClassPlan.of(Mixed.class), ClassPlan.of(Mixed.class));
    }
}
