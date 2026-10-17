package org.techhouse.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;

public class EJsonTransientFieldTest {
    private final EJson eJson = new EJson();

    @Test
    public void aTransientFieldIsNotEmitted() {
        final var carrier = new Carrier();
        carrier.name = "visible";
        carrier.nodeLocal = "hidden";

        final var json = eJson.toJson(carrier);

        assertFalse(json.contains("nodeLocal"), json);
        assertFalse(json.contains("hidden"), json);
    }

    @Test
    public void aTransientFieldIsNotAssignedFromInput() {
        final var carrier = eJson.fromJson("{\"name\":\"visible\",\"nodeLocal\":\"injected\"}", Carrier.class);

        assertEquals("visible", carrier.name);
        assertNull(carrier.nodeLocal);
    }

    private static final class Carrier {
        public String name;
        public transient String nodeLocal;
    }
}
