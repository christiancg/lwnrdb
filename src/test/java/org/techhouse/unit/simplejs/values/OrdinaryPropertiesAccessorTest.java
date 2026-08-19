package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.PropertyDescriptor;

// Covers OrdinaryProperties.applyAccessorFields' handling of a descriptor whose [[Get]]/[[Set]]
// fields are both present but resolve to a non-callable value (typically undefined, as in
// `Object.defineProperty(o, "p", {get: undefined, set: undefined})`). Per spec that must still
// create a genuine accessor property - reads as undefined, rejects writes, and is redefine-checked
// as an accessor rather than a data property - not a data property whose value happens to be
// undefined. Regression coverage for the bug root-caused in Wave 8 and fixed here.
public class OrdinaryPropertiesAccessorTest {
    private static PropertyDescriptor bothUndefinedAccessor(Boolean enumerable, Boolean configurable) {
        return new PropertyDescriptor(null, JsUndefined.getInstance(), JsUndefined.getInstance(), null, enumerable,
                configurable);
    }

    // desc.hasOwnProperty("get") must be true - i.e. the resulting descriptor really is an accessor
    // descriptor, not a data descriptor whose value happens to be undefined.
    @Test
    public void test_both_sides_undefined_creates_a_real_accessor_property() {
        final var object = new JsObject();
        object.defineOwnProperty(new JsString("prop"), bothUndefinedAccessor(true, false));
        final var descriptor = object.getOwnProperty(new JsString("prop"));
        assertNotNull(descriptor);
        assertTrue(descriptor.isAccessorDescriptor());
        assertNotNull(descriptor.getter());
        assertNotNull(descriptor.setter());
        assertFalse(object.has("prop"));
        assertTrue(object.hasAccessor("prop"));
        assertNull(object.getAccessorGetter("prop"));
        assertNull(object.getAccessorSetter("prop"));
    }

    // A non-configurable accessor property rejects a redefine into a data property, exactly like a
    // non-configurable accessor with real getter/setter functions would (built-ins/Object/
    // defineProperty/15.2.3.6-4-457.js, defineProperties/15.2.3.7-6-a-243.js's underlying check).
    @Test
    public void test_non_configurable_both_sides_undefined_accessor_rejects_data_redefine() {
        final var object = new JsObject();
        object.defineOwnProperty(new JsString("prop"), bothUndefinedAccessor(false, false));
        final var dataRedefine = new PropertyDescriptor(new JsNumber(1001), null, null, null, null, null);
        assertThrows(TypeErrorException.class, () -> object.defineOwnProperty(new JsString("prop"), dataRedefine));
        // The failed redefine must not have partially applied.
        final var stillAccessor = object.getOwnProperty(new JsString("prop"));
        assertNotNull(stillAccessor);
        assertTrue(stillAccessor.isAccessorDescriptor());
    }

    // A configurable accessor property (even with both sides undefined) can be redefined into a
    // data property (built-ins/Object/defineProperty/15.2.3.6-4-430.js and -448.js).
    @Test
    public void test_configurable_both_sides_undefined_accessor_allows_data_redefine() {
        final var object = new JsObject();
        object.defineOwnProperty(new JsString("prop"), bothUndefinedAccessor(true, true));
        final var dataRedefine = new PropertyDescriptor(new JsNumber(1001), null, null, null, null, null);
        object.defineOwnProperty(new JsString("prop"), dataRedefine);
        final var descriptor = object.getOwnProperty(new JsString("prop"));
        assertNotNull(descriptor);
        assertFalse(descriptor.isAccessorDescriptor());
        assertTrue(object.has("prop"));
        assertFalse(object.hasAccessor("prop"));
        assertEquals(1001, ((JsNumber) descriptor.value()).getValue());
    }

    // Re-applying the identical {get: undefined, set: undefined} descriptor to an already-existing
    // non-configurable no-sides accessor must be a legal no-op redefine (identity check passes
    // because both the existing and the new getter/setter resolve to the same "absent" state),
    // mirroring a defineProperties idempotent re-application.
    @Test
    public void test_reapplying_the_same_both_undefined_descriptor_does_not_throw() {
        final var object = new JsObject();
        object.defineOwnProperty(new JsString("prop"), bothUndefinedAccessor(false, false));
        object.defineOwnProperty(new JsString("prop"), bothUndefinedAccessor(false, false));
        final var descriptor = object.getOwnProperty(new JsString("prop"));
        assertNotNull(descriptor);
        assertTrue(descriptor.isAccessorDescriptor());
    }

    // A one-sided {set: undefined} descriptor (get absent entirely, set present-but-undefined) is
    // the same underlying bug: it must still register as an accessor rather than a data property.
    @Test
    public void test_one_sided_set_undefined_creates_a_real_accessor_property() {
        final var object = new JsObject();
        final var setOnlyUndefined = new PropertyDescriptor(null, null, JsUndefined.getInstance(), null, false, false);
        object.defineOwnProperty(new JsString("prop"), setOnlyUndefined);
        assertTrue(object.hasAccessor("prop"));
        assertFalse(object.has("prop"));
        final var reapplied = object.defineOwnProperty(new JsString("prop"), setOnlyUndefined);
        assertTrue(reapplied);
    }
}
