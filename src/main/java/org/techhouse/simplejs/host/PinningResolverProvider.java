package org.techhouse.simplejs.host;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

public final class PinningResolverProvider extends InetAddressResolverProvider {
    public static final String NAME = "lwnrdb-pinning";

    @Override
    public InetAddressResolver get(InetAddressResolverProvider.Configuration configuration) {
        return new PinningResolver(configuration.builtinResolver());
    }

    @Override
    public String name() {
        return NAME;
    }
}
