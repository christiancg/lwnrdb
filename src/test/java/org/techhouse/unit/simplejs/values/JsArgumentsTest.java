package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.PropertyDescriptor;

public class JsArgumentsTest {
    private static Environment environmentWith() {
        final var env = Environment.global();
        env.declareVar("a");
        env.assign("a", new JsNumber(1));
        return env;
    }

    private static JsArguments mapped(Environment env) {
        return new JsArguments(List.of(new JsNumber(1)), List.of("a"), env);
    }

    private static List<String> keyNames(JsArguments arguments) {
        return arguments.ownPropertyKeys().stream()
                .map(key -> key instanceof JsString string ? string.getValue() : "@@iterator").toList();
    }

    @Test
    public void test_define_property_on_index_unmaps_binding() {
        final var env = environmentWith();
        final var arguments = mapped(env);
        arguments.defineOwnProperty(new JsString("0"),
                new PropertyDescriptor(new JsNumber(5), null, null, false, true, true));
        assertEquals(5, ((JsNumber) env.get("a")).getValue());
        env.assign("a", new JsNumber(99));
        assertEquals(5, ((JsNumber) arguments.get(0)).getValue());
    }

    @Test
    public void test_define_accessor_on_index_unmaps_binding() {
        final var env = environmentWith();
        final var arguments = mapped(env);
        final var getter = new JsNativeFunction("get", (_, _) -> new JsNumber(7));
        arguments.defineOwnProperty(new JsString("0"), new PropertyDescriptor(null, getter, null, null, true, true));
        env.assign("a", new JsNumber(42));
        final var descriptor = arguments.getOwnProperty(new JsString("0"));
        assertNotNull(descriptor);
        assertTrue(descriptor.isAccessorDescriptor());
        assertEquals(getter, descriptor.getter());
    }

    @Test
    public void test_define_non_writable_without_value_keeps_mapped_value() {
        final var env = environmentWith();
        final var arguments = mapped(env);
        env.assign("a", new JsNumber(33));
        arguments.defineOwnProperty(new JsString("0"), new PropertyDescriptor(null, null, null, false, null, null));
        env.assign("a", new JsNumber(44));
        assertEquals(33, ((JsNumber) arguments.get(0)).getValue());
    }

    @Test
    public void test_delete_index_removes_own_property_and_mapping() {
        final var env = environmentWith();
        final var arguments = mapped(env);
        assertTrue(arguments.deleteOwnProperty(new JsString("0")));
        assertNull(arguments.getOwnProperty(new JsString("0")));
        assertInstanceOf(JsUndefined.class, arguments.get(0));
        env.assign("a", new JsNumber(8));
        assertInstanceOf(JsUndefined.class, arguments.get(0));
    }

    @Test
    public void test_delete_of_non_configurable_index_is_rejected() {
        final var arguments = new JsArguments(List.of(new JsNumber(1)), null, null);
        arguments.defineOwnProperty(new JsString("0"),
                PropertyDescriptor.data(new JsNumber(1), new JsObject.PropertyFlags(false, false, false)));
        assertFalse(arguments.deleteOwnProperty(new JsString("0")));
        assertNotNull(arguments.getOwnProperty(new JsString("0")));
    }

    @Test
    public void test_own_keys_list_indices_then_length_then_callee() {
        final var arguments = new JsArguments(List.of(new JsNumber(1), new JsNumber(2)), null, null);
        arguments.ownProperties().defineValue("callee", JsUndefined.getInstance());
        arguments.ownProperties().defineSymbolValue(JsSymbol.ITERATOR, JsUndefined.getInstance());
        assertEquals(List.of("0", "1", "length", "callee", "@@iterator"), keyNames(arguments));
    }

    @Test
    public void test_length_descriptor_is_writable_non_enumerable_configurable() {
        final var arguments = new JsArguments(List.of(new JsNumber(1), new JsNumber(2)), null, null);
        final var descriptor = arguments.getOwnProperty(new JsString("length"));
        assertNotNull(descriptor);
        assertEquals(2, ((JsNumber) descriptor.value()).getValue());
        assertTrue(descriptor.writableOr(false));
        assertFalse(descriptor.enumerableOr(true));
        assertTrue(descriptor.configurableOr(false));
        assertEquals(2, arguments.length());
    }

    @Test
    public void test_index_descriptor_reads_through_the_mapping() {
        final var env = environmentWith();
        final var arguments = mapped(env);
        env.assign("a", new JsNumber(21));
        final var descriptor = arguments.getOwnProperty(new JsString("0"));
        assertNotNull(descriptor);
        assertEquals(21, ((JsNumber) descriptor.value()).getValue());
        assertTrue(descriptor.enumerableOr(false));
        assertNull(arguments.getOwnProperty(new JsString("nope")));
        assertNull(arguments.getOwnProperty(JsSymbol.ITERATOR));
    }

    @Test
    public void test_named_property_is_ordinary() {
        final var arguments = new JsArguments(List.of(new JsNumber(1)), null, null);
        assertTrue(arguments.setProperty("gp", new JsNumber(3)));
        assertTrue(arguments.hasOwnKey(new JsString("gp")));
        assertEquals(List.of("0", "gp"), arguments.enumerablePropertyKeys());
        assertInstanceOf(JsUndefined.class, arguments.get(-1));
        assertFalse(arguments.set(-1, new JsNumber(0)));
    }

    @Test
    public void test_snapshot_follows_the_length_property() {
        final var arguments = new JsArguments(List.of(new JsNumber(1), new JsNumber(2), new JsNumber(3)), null, null);
        assertEquals(3, arguments.snapshot().size());
        assertTrue(arguments.setProperty("length", new JsNumber(2)));
        assertEquals(2, arguments.snapshot().size());
        assertTrue(arguments.setProperty("length", new JsNumber(-1)));
        assertEquals(0, arguments.snapshot().size());
    }

    @Test
    public void test_parameter_map_stops_at_the_supplied_arguments() {
        final var env = environmentWith();
        env.declareVar("b");
        env.assign("b", new JsNumber(2));
        final var arguments = new JsArguments(List.of(new JsNumber(1)), List.of("a", "b"), env);
        assertNull(arguments.getOwnProperty(new JsString("1")));
        assertTrue(arguments.set(0, new JsNumber(6)));
        assertEquals(6, ((JsNumber) env.get("a")).getValue());
        assertEquals(2, ((JsNumber) env.get("b")).getValue());
    }
}
