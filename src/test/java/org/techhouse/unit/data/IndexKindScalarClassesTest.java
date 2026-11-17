package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.unit.ejson.custom_types.CustomTypeFactoryTest;

public class IndexKindScalarClassesTest {
    private final Map<String, Class<? extends JsonCustom<?>>> registeredBefore = new HashMap<>();

    @BeforeEach
    public void setUp() {
        registeredBefore.putAll(CustomTypeFactory.getCustomTypes());
    }

    @AfterEach
    public void restoreTheRegistry() {
        CustomTypeFactory.getCustomTypes().clear();
        CustomTypeFactory.getCustomTypes().putAll(registeredBefore);
    }

    @Test
    public void repeatedCallsShareOneImmutableList() {
        final var first = IndexKind.scalarIndexClasses();

        assertSame(first, IndexKind.scalarIndexClasses());
        assertEquals(List.of(Number.class, Boolean.class, String.class), first.subList(0, 3));
        assertEquals(3 + registeredBefore.size(), first.size());
        assertThrows(UnsupportedOperationException.class, () -> first.add(Object.class));
    }

    @Test
    public void aRegistrationIsSeenByTheNextCall() {
        final var before = IndexKind.scalarIndexClasses();
        CustomTypeFactory.registerCustomType(CustomTypeFactoryTest.ValidCustomType.class);

        final var after = IndexKind.scalarIndexClasses();

        assertNotSame(before, after);
        assertTrue(after.contains(CustomTypeFactoryTest.ValidCustomType.class));
    }

    @Test
    public void aRegistryEditedInPlaceIsSeenByTheNextCall() {
        IndexKind.scalarIndexClasses();
        CustomTypeFactory.getCustomTypes().clear();

        assertEquals(List.of(Number.class, Boolean.class, String.class), IndexKind.scalarIndexClasses());

        CustomTypeFactory.getCustomTypes().putAll(registeredBefore);
        assertEquals(3 + registeredBefore.size(), IndexKind.scalarIndexClasses().size());
    }

    @Test
    public void aTypeReplacedUnderTheSameNameIsSeenByTheNextCall() {
        final var replaced = registeredBefore.entrySet().iterator().next();
        IndexKind.scalarIndexClasses();
        CustomTypeFactory.getCustomTypes().put(replaced.getKey(), CustomTypeFactoryTest.ValidCustomType.class);

        final var classes = IndexKind.scalarIndexClasses();

        assertTrue(classes.contains(CustomTypeFactoryTest.ValidCustomType.class));
        assertFalse(classes.contains(replaced.getValue()));
    }
}
