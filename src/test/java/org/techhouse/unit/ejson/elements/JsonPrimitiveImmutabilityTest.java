package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

public class JsonPrimitiveImmutabilityTest {
    @BeforeAll
    public static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    @Test
    public void deepCopyOfPrimitivesReturnsTheSameInstance() {
        final var string = new JsonString("text");
        final var number = new JsonNumber(1.5);

        assertSame(string, string.deepCopy());
        assertSame(number, number.deepCopy());
        assertSame(JsonBoolean.TRUE, JsonBoolean.TRUE.deepCopy());
        assertSame(JsonNull.INSTANCE, JsonNull.INSTANCE.deepCopy());
    }

    @Test
    public void containersStillCopyButShareTheirPrimitives() {
        final var original = IocContainer.get(EJson.class).fromJson("{\"a\":\"x\",\"b\":{\"c\":true}}",
                JsonObject.class);

        final var copy = original.deepCopy();

        assertNotSame(original, copy);
        assertNotSame(original.get("b"), copy.get("b"));
        assertSame(original.get("a"), copy.get("a"));
        assertSame(JsonBoolean.TRUE, copy.get("b").asJsonObject().get("c"));
        assertEquals(original, copy);
    }

    @Test
    public void customDeepCopyStillCopies() {
        final var vector = new JsonVector("#vector(1,2,3)");

        final var copy = (JsonVector) vector.deepCopy();

        assertNotSame(vector, copy);
        assertNotSame(vector.getCustomValue(), copy.getCustomValue());
        assertArrayEquals(vector.getCustomValue(), copy.getCustomValue());
        copy.getCustomValue()[0] = 9;
        assertEquals(1.0, vector.getCustomValue()[0]);
    }

    @Test
    public void booleansAreTheTwoSingletons() {
        final var parsed = IocContainer.get(EJson.class).fromJson("{\"t\":true,\"f\":false}", JsonObject.class);

        assertSame(JsonBoolean.TRUE, parsed.get("t"));
        assertSame(JsonBoolean.FALSE, parsed.get("f"));
        assertSame(JsonBoolean.TRUE, JsonBoolean.of(true));
        assertSame(JsonBoolean.FALSE, JsonBoolean.of(false));
        final var object = new JsonObject();
        object.addProperty("flag", true);
        assertSame(JsonBoolean.TRUE, object.get("flag"));
    }

    @Test
    public void noPublicMutatorRemains() {
        final var setters = Arrays.stream(JsonPrimitive.class.getMethods())
                .filter(method -> method.getName().startsWith("set")).toList();

        assertTrue(setters.isEmpty(), "JsonPrimitive exposes " + setters);
        assertTrue(Arrays.stream(JsonBoolean.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }
}
