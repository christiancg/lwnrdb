package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        final var bindings = DatabaseHostBindings.of(new JsonObject(), null, null, ResourceLimits.unlimited(), null);
        assertEquals(ZoneId.of(configuration.getScriptTimeZone()), bindings.timeZone());
        assertEquals(Locale.forLanguageTag(configuration.getScriptLocale()), bindings.locale());
        assertEquals(ZoneId.of("UTC"), bindings.timeZone());
        assertEquals(Locale.US, bindings.locale());
    }

    // The remaining HostBindings members are the record's own components. network() is not one of them -
    // it follows scriptFetchEnabled, which the test below pins in both directions.
    @Test
    public void test_carries_the_rest_of_the_contract() {
        final var args = new JsonObject();
        final var limits = ResourceLimits.unlimited();
        final var bindings = DatabaseHostBindings.of(args, null, System.out::println, limits, null);
        assertSame(args, bindings.args());
        assertNull(bindings.database());
        assertNotNull(bindings.console());
        assertSame(limits, bindings.limits());
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

    // fetch is unreachable unless configuration grants it, and the grant carries the allowlist with it.
    @org.junit.jupiter.api.Test
    public void test_network_is_absent_unless_script_fetch_is_enabled() throws Exception {
        final var configuration = org.techhouse.config.Configuration.getInstance();
        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchEnabled", false);
        assertNull(org.techhouse.simplejs.host.DatabaseHostBindings
                .of(new org.techhouse.ejson.elements.JsonObject(), null, null, null, null).network());
        assertFalse(org.techhouse.simplejs.host.DatabaseHostBindings.limitsFromConfiguration().fetchEnabled());

        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchEnabled", true);
        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchAllowlistRaw",
                "api.example.com, *.internal");
        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchTimeoutMs", 4321L);
        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchMaxResponseBytes", 12345L);

        assertNotNull(org.techhouse.simplejs.host.DatabaseHostBindings
                .of(new org.techhouse.ejson.elements.JsonObject(), null, null, null, null).network());
        final var limits = org.techhouse.simplejs.host.DatabaseHostBindings.limitsFromConfiguration();
        assertTrue(limits.fetchEnabled());
        assertEquals(java.util.List.of("api.example.com", "*.internal"), limits.fetchHostAllowlist());
        assertEquals(4321L, limits.fetchTimeoutMillis());
        assertEquals(12345L, limits.maxResponseBytes());

        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchEnabled", false);
    }

    // A trigger and a schedule rebuild the limits with their own wall clock; the fetch grant must survive
    // that rebuild or the capability would silently vanish for exactly the runs that need it most.
    @org.junit.jupiter.api.Test
    public void test_trigger_and_schedule_limits_keep_the_fetch_grant() throws Exception {
        final var configuration = org.techhouse.config.Configuration.getInstance();
        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchEnabled", true);
        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchAllowlistRaw", "api.example.com");
        org.techhouse.test.TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 1234L);

        final var base = org.techhouse.simplejs.host.DatabaseHostBindings.limitsFromConfiguration();
        final var rebuilt = new org.techhouse.simplejs.host.ResourceLimits(base.instructionBudget(), 1234L,
                base.maxDepth(), base.reportUnhandledRejections(), base.fetchEnabled(), base.fetchHostAllowlist(),
                base.maxResponseBytes(), base.fetchTimeoutMillis(), base.strictScriptGoal(), base.textImportEnabled(),
                base.maxModuleDepth(), base.maxLogLines(), base.maxLogLineChars(), base.memoryBudget(), -1,
                base.cursorBatchSize(), base.cursorMaxBatchSize());

        assertTrue(rebuilt.fetchEnabled());
        assertEquals(java.util.List.of("api.example.com"), rebuilt.fetchHostAllowlist());

        org.techhouse.test.TestUtils.setPrivateField(configuration, "scriptFetchEnabled", false);
    }
}
