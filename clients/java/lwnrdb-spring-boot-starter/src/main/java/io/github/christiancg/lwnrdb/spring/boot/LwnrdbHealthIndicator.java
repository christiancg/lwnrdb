package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

@NullMarked
public class LwnrdbHealthIndicator extends AbstractHealthIndicator {
    private static final String READY = "READY";
    private static final String STATE = "state";

    private final LwnrdbClient client;

    public LwnrdbHealthIndicator(LwnrdbClient client) {
        super("LWNRDB health check failed");
        this.client = client;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        final var info = client.serverInfo();
        final var state = info.state().orElse("UNKNOWN");
        if (READY.equals(state)) {
            builder.up();
        } else {
            builder.outOfService();
        }
        builder.withDetail(STATE, state);
        info.version().ifPresent(version -> builder.withDetail("version", version));
    }
}
