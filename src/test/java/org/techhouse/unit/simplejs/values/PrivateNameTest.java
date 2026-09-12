package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.PrivateName;

public class PrivateNameTest {
    @Test
    public void test_private_names_are_identity_keyed() {
        final var first = new PrivateName("#x");
        final var second = new PrivateName("#x");
        assertNotSame(first, second);
        assertNotEquals(first, second);
        assertEquals("#x", first.description());
        assertEquals("#x", first.toString());
    }

    @Test
    public void test_each_class_evaluation_creates_its_own_slot() {
        final var first = new JsClass("C", null, Environment.global());
        final var second = new JsClass("C", null, Environment.global());
        final var firstName = first.declarePrivateName("x");
        final var secondName = second.declarePrivateName("x");
        assertNotSame(firstName, secondName);
        assertSame(firstName, first.declarePrivateName("x"));
        assertSame(firstName, first.privateNameFor("x"));
        assertNull(first.privateNameFor("y"));
    }

    @Test
    public void test_private_fields_do_not_collide_across_classes() {
        final var first = new JsClass("C", null, Environment.global());
        final var second = new JsClass("C", null, Environment.global());
        final var object = new JsObject();
        assertTrue(object.addPrivate(first.declarePrivateName("x"), new JsNumber(1)));
        assertTrue(object.hasPrivate(first.privateNameFor("x")));
        assertFalse(object.hasPrivate(second.declarePrivateName("x")));
        assertEquals(1, ((JsNumber) Objects.requireNonNull(object.getPrivate(first.privateNameFor("x")))).getValue());
    }

    @Test
    public void test_add_private_rejects_a_duplicate_and_a_sealed_object() {
        final var cls = new JsClass("C", null, Environment.global());
        final var name = cls.declarePrivateName("x");
        final var object = new JsObject();
        assertTrue(object.addPrivate(name, new JsNumber(1)));
        assertFalse(object.addPrivate(name, new JsNumber(2)));
        final var frozen = new JsObject();
        frozen.preventExtensions();
        assertFalse(frozen.addPrivate(cls.declarePrivateName("y"), new JsNumber(1)));
    }

    @Test
    public void test_private_brand_is_added_once() {
        final var cls = new JsClass("C", null, Environment.global());
        final var object = new JsObject();
        assertTrue(object.addPrivateBrand(cls));
        assertFalse(object.addPrivateBrand(cls));
        assertTrue(object.hasPrivateBrand(cls));
        assertFalse(object.hasPrivateBrand(new JsClass("D", null, Environment.global())));
    }
}
