package org.techhouse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.simplejs.host.PinningResolver;

public class FetchPinningGuardTest {
    @Test
    public void test_fetch_without_an_active_pinning_resolver_refuses_to_start() {
        assertThrows(InvalidConfigurationException.class,
                () -> FetchPinningGuard.refuseUnpinnedFetch(true, () -> false));
    }

    @Test
    public void test_fetch_disabled_or_pinned_starts() {
        assertDoesNotThrow(() -> FetchPinningGuard.refuseUnpinnedFetch(false, () -> false));
        assertDoesNotThrow(() -> FetchPinningGuard.refuseUnpinnedFetch(true, () -> true));
    }

    @Test
    public void test_the_shipped_classpath_installs_the_pinning_resolver() {
        assertTrue(PinningResolver.isInstalled());
        FetchPinningGuard.refuseUnpinnedFetch();
    }
}
