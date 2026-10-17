package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.PinnedResolution;
import org.techhouse.simplejs.host.PinningResolverProvider;

public class PinningResolverProviderTest {
    @Test
    public void test_service_loader_finds_the_provider() {
        final var providers = ServiceLoader.load(InetAddressResolverProvider.class).stream()
                .map(ServiceLoader.Provider::get).toList();
        assertTrue(providers.stream().anyMatch(PinningResolverProvider.class::isInstance), providers.toString());
        assertEquals(PinningResolverProvider.NAME, new PinningResolverProvider().name());
    }

    @Test
    public void test_the_jvm_resolves_a_pinned_name_through_the_provider() throws UnknownHostException {
        final var pinned = InetAddress.ofLiteral("192.0.2.77");
        try (var _ = PinnedResolution.pin("lwnrdb-pinned-jvm.invalid", List.of(pinned))) {
            assertEquals(pinned.getHostAddress(), InetAddress.getByName("lwnrdb-pinned-jvm.invalid").getHostAddress());
        }
    }
}
