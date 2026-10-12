package org.techhouse.simplejs.host;

import static java.net.spi.InetAddressResolver.LookupPolicy.IPV4;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV4_FIRST;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV6;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV6_FIRST;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PinningResolver implements InetAddressResolver {
    private final InetAddressResolver delegate;

    public PinningResolver(InetAddressResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
        final var pinned = PinnedResolution.lookup(host);
        if (pinned == null) {
            return delegate.lookupByName(host, lookupPolicy);
        }
        final var matching = matching(pinned, lookupPolicy.characteristics());
        if (matching.isEmpty()) {
            throw new UnknownHostException(host);
        }
        return matching.stream();
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
        return delegate.lookupByAddress(addr);
    }

    static List<InetAddress> matching(List<InetAddress> pinned, int characteristics) {
        final var allowed = pinned.stream()
                .filter(address -> address instanceof Inet4Address
                        ? (characteristics & IPV4) != 0
                        : (characteristics & IPV6) != 0);
        if ((characteristics & IPV4_FIRST) != 0) {
            return allowed.sorted(Comparator.comparing(address -> address instanceof Inet4Address ? 0 : 1)).toList();
        }
        if ((characteristics & IPV6_FIRST) != 0) {
            return allowed.sorted(Comparator.comparing(address -> address instanceof Inet4Address ? 1 : 0)).toList();
        }
        return allowed.toList();
    }
}
