package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.PinnedResolution;
import org.techhouse.simplejs.host.PinningResolver;

public class PinnedResolutionTest {
    private static final InetAddress PRIVATE = InetAddress.ofLiteral("10.0.0.7");
    private static final InetAddress PUBLIC_V4 = InetAddress.ofLiteral("8.8.8.8");
    private static final InetAddress PUBLIC_V6 = InetAddress.ofLiteral("2606:4700:4700::1111");
    private static final int ALL = LookupPolicy.IPV4 | LookupPolicy.IPV6;

    private static final class Delegate implements InetAddressResolver {
        private final List<String> asked = new ArrayList<>();

        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) {
            asked.add(host);
            return Stream.of(PRIVATE);
        }

        @Override
        public String lookupByAddress(byte[] addr) {
            return "reverse.example";
        }
    }

    @Test
    public void test_pin_returns_the_stored_list_and_close_removes_it() {
        try (var _ = PinnedResolution.pin("pinned-one.example", List.of(PUBLIC_V4))) {
            assertEquals(List.of(PUBLIC_V4), PinnedResolution.lookup("pinned-one.example"));
        }
        assertNull(PinnedResolution.lookup("pinned-one.example"));
    }

    @Test
    public void test_lookup_is_case_insensitive() {
        try (var _ = PinnedResolution.pin("Pinned-Case.Example", List.of(PUBLIC_V4))) {
            assertEquals(List.of(PUBLIC_V4), PinnedResolution.lookup("PINNED-case.example"));
        }
        assertNull(PinnedResolution.lookup(null));
    }

    @Test
    public void test_refcount_keeps_the_first_pin_until_the_last_close() {
        final var first = PinnedResolution.pin("pinned-shared.example", List.of(PUBLIC_V4));
        final var second = PinnedResolution.pin("pinned-shared.example", List.of(PUBLIC_V6));
        assertEquals(List.of(PUBLIC_V4), PinnedResolution.lookup("pinned-shared.example"));
        first.close();
        first.close();
        assertEquals(List.of(PUBLIC_V4), PinnedResolution.lookup("pinned-shared.example"));
        second.close();
        assertNull(PinnedResolution.lookup("pinned-shared.example"));
    }

    @Test
    public void test_concurrent_pin_and_close_leaves_no_entry() throws InterruptedException {
        final var start = new CountDownLatch(1);
        final var threads = new ArrayList<Thread>();
        for (var i = 0; i < 32; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                for (var round = 0; round < 200; round++) {
                    try (var _ = PinnedResolution.pin("pinned-race.example", List.of(PUBLIC_V4))) {
                        assertEquals(List.of(PUBLIC_V4), PinnedResolution.lookup("pinned-race.example"));
                    }
                }
            }));
        }
        start.countDown();
        for (final var thread : threads) {
            thread.join();
        }
        assertNull(PinnedResolution.lookup("pinned-race.example"));
    }

    @Test
    public void test_resolver_answers_pinned_hosts_and_delegates_the_rest() throws UnknownHostException {
        final var delegate = new Delegate();
        final var resolver = new PinningResolver(delegate);
        try (var _ = PinnedResolution.pin("pinned-resolver.example", List.of(PUBLIC_V6, PUBLIC_V4))) {
            assertEquals(List.of(PUBLIC_V6, PUBLIC_V4),
                    resolver.lookupByName("pinned-resolver.example", LookupPolicy.of(ALL)).toList());
            assertEquals(List.of(PUBLIC_V4, PUBLIC_V6), resolver
                    .lookupByName("pinned-resolver.example", LookupPolicy.of(ALL | LookupPolicy.IPV4_FIRST)).toList());
            assertEquals(List.of(PUBLIC_V6, PUBLIC_V4), resolver
                    .lookupByName("pinned-resolver.example", LookupPolicy.of(ALL | LookupPolicy.IPV6_FIRST)).toList());
            assertEquals(List.of(PUBLIC_V4),
                    resolver.lookupByName("pinned-resolver.example", LookupPolicy.of(LookupPolicy.IPV4)).toList());
        }
        try (var _ = PinnedResolution.pin("pinned-v4-only.example", List.of(PUBLIC_V4))) {
            assertThrows(UnknownHostException.class,
                    () -> resolver.lookupByName("pinned-v4-only.example", LookupPolicy.of(LookupPolicy.IPV6)));
        }
        assertEquals(List.of(PRIVATE), resolver.lookupByName("unpinned.example", LookupPolicy.of(ALL)).toList());
        assertEquals(List.of("unpinned.example"), delegate.asked);
        assertEquals("reverse.example", resolver.lookupByAddress(PUBLIC_V4.getAddress()));
    }
}
