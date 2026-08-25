package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.host.BulkSaveOutcome;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.ResourceLimits;

public class DatabaseHostBindingsTest {
    // The binding pins the zone and locale from configuration, unlike SimpleHostBindings
    @Test
    public void test_reads_the_script_zone_and_locale_from_configuration() {
        final var configuration = Configuration.getInstance();
        final var bindings = DatabaseHostBindings.of(new JsonObject(), null, null, ResourceLimits.unlimited());
        assertEquals(ZoneId.of(configuration.getScriptTimeZone()), bindings.timeZone());
        assertEquals(Locale.forLanguageTag(configuration.getScriptLocale()), bindings.locale());
        assertEquals(ZoneId.of("UTC"), bindings.timeZone());
        assertEquals(Locale.US, bindings.locale());
    }

    // The remaining HostBindings members are the record's own components
    @Test
    public void test_carries_the_rest_of_the_contract() {
        final var args = new JsonObject();
        final var limits = ResourceLimits.unlimited();
        final var bindings = DatabaseHostBindings.of(args, null, System.out::println, limits);
        assertSame(args, bindings.args());
        assertNull(bindings.database());
        assertNotNull(bindings.console());
        assertSame(limits, bindings.limits());
        assertNull(bindings.network());
    }

    // With no seam the readers answer the JVM defaults, which is what a null-ops getMethod sees
    @Test
    public void test_ops_readers_fall_back_to_the_jvm_defaults() {
        assertEquals(ZoneId.systemDefault(), InterpreterOps.timeZone(null));
        assertEquals(Locale.getDefault(), InterpreterOps.locale(null));
    }

    // The sandbox's memory budget comes from configuration, never from the caller
    @Test
    public void test_reads_the_memory_budget_from_configuration() {
        final var configuration = Configuration.getInstance();
        final var limits = DatabaseHostBindings.limitsFromConfiguration();
        assertEquals(configuration.getScriptMaxMemoryBytes(), limits.memoryBudget());
    }

    // A null id list is normalised to an empty one rather than surfacing as null
    @Test
    public void test_bulk_save_outcome_normalises_nulls() {
        final var outcome = new BulkSaveOutcome(null, null);
        assertEquals(List.of(), outcome.inserted());
        assertEquals(List.of(), outcome.updated());
    }
}
