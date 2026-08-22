package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class HostBindingsLocaleTest {
    private record PinnedBindings(ZoneId timeZone, Locale locale) implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }

        @Override
        public Consumer<String> console() {
            return null;
        }

        @Override
        public ResourceLimits limits() {
            return ResourceLimits.unlimited();
        }
    }

    private static String run(HostBindings bindings, String source) {
        final var result = new SimpleJs().run(source, bindings);
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return result.getValue().asJsonString().getValue();
    }

    // The contract's defaults are the JVM's, which is what keeps the test262 worker's behaviour
    // untouched by this feature (SimpleHostBindings deliberately does not override them).
    @Test
    public void test_defaults_are_the_jvm_values() {
        assertEquals(ZoneId.systemDefault(), SimpleHostBindings.empty().timeZone());
        assertEquals(Locale.getDefault(), SimpleHostBindings.empty().locale());
    }

    // Date's local-time surface answers in the host's zone
    @Test
    public void test_time_zone_drives_date() {
        final var utc = new PinnedBindings(ZoneId.of("UTC"), Locale.US);
        final var tokyo = new PinnedBindings(ZoneId.of("Asia/Tokyo"), Locale.US);
        assertTrue(run(utc, "return new Date(0).toString();").contains("00:00:00"));
        assertTrue(run(tokyo, "return new Date(0).toString();").contains("09:00:00"));
        assertEquals("0", run(utc, "return String(new Date(0).getTimezoneOffset());"));
        assertEquals("-540", run(tokyo, "return String(new Date(0).getTimezoneOffset());"));
    }

    // Temporal.Now reports the host's zone rather than the JVM's
    @Test
    public void test_time_zone_drives_temporal_now() {
        assertEquals("Asia/Tokyo",
                run(new PinnedBindings(ZoneId.of("Asia/Tokyo"), Locale.US), "return Temporal.Now.timeZoneId();"));
        assertEquals("UTC", run(new PinnedBindings(ZoneId.of("UTC"), Locale.US), "return Temporal.Now.timeZoneId();"));
    }

    // Number formatting and collation follow the host's locale
    @Test
    public void test_locale_drives_formatting_and_collation() {
        final var us = new PinnedBindings(ZoneId.of("UTC"), Locale.US);
        final var germany = new PinnedBindings(ZoneId.of("UTC"), Locale.GERMANY);
        assertNotEquals(run(us, "return (1234.5).toLocaleString();"),
                run(germany, "return (1234.5).toLocaleString();"));
        assertEquals("-1", run(us, "return String('a'.localeCompare('b'));"));
        assertEquals("A", run(new PinnedBindings(ZoneId.of("UTC"), Locale.forLanguageTag("tr-TR")),
                "return 'a'.toLocaleUpperCase();"));
    }
}
