package io.github.christiancg.lwnrdb.spring.boot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.spring.boot.LwnrdbHealthIndicator;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;

public class StarterIT {
    @Test
    public void test_the_health_indicator_reports_the_server() throws Exception {
        try (var server = ServerProcess.start(Map.of()); var context = SpringServer.start(server, Map.of())) {
            final var health = health(context, true);
            assertEquals(Status.UP, health.getStatus());
            assertEquals("READY", health.getDetails().get("state"));
            assertTrue(health.getDetails().containsKey("version"));
        }
    }

    @Test
    public void test_a_wrong_password_fails_startup() throws Exception {
        try (var server = ServerProcess.start(Map.of())) {
            final var failure = assertThrows(RuntimeException.class,
                    () -> SpringServer.start(server, Map.of("lwnrdb.password", "wrong-password")));
            assertTrue(causedByAnUnreachableServer(failure), failure.toString());
        }
    }

    @Test
    public void test_tls_with_an_insecure_trust_connects() throws Exception {
        try (var server = ServerProcess.start(Map.of("tlsEnabled", "true", "tlsKeystorePath", "certs/lwnrdb.p12",
                "tlsKeystorePassword", "change_it"));
                var context = SpringServer.start(server,
                        Map.of("lwnrdb.tls.enabled", "true", "lwnrdb.tls.insecure", "true"))) {
            assertEquals(Status.UP, health(context, false).getStatus());
        }
    }

    private static Health health(ApplicationContext context, boolean includeDetails) {
        return Objects.requireNonNull(context.getBean(LwnrdbHealthIndicator.class).health(includeDetails));
    }

    private static boolean causedByAnUnreachableServer(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException) {
                return true;
            }
        }
        return false;
    }
}
