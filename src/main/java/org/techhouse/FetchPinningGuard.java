package org.techhouse;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.techhouse.config.Configuration;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.log.Logger;
import org.techhouse.simplejs.host.PinningResolver;

public final class FetchPinningGuard {
    static final String REASON = "scriptFetchEnabled is true but the DNS pinning resolver is not active, so a script's"
            + " fetch could be re-pointed at a private address between the check and the connect. Run the server from"
            + " the shaded jar (META-INF/services must be on the classpath) or set scriptFetchEnabled=false.";
    private static final Logger logger = Logger.logFor(FetchPinningGuard.class);
    private static final Configuration configuration = Configuration.getInstance();

    private FetchPinningGuard() {
    }

    public static void refuseUnpinnedFetch() {
        refuseUnpinnedFetch(configuration.isScriptFetchEnabled(), PinningResolver::isInstalled);
    }

    static void refuseUnpinnedFetch(boolean fetchEnabled, BooleanSupplier pinningInstalled) {
        if (fetchEnabled && !pinningInstalled.getAsBoolean()) {
            logger.fatal("Insecure configuration, the application will not start: " + REASON);
            throw new InvalidConfigurationException(List.of(REASON));
        }
    }
}
