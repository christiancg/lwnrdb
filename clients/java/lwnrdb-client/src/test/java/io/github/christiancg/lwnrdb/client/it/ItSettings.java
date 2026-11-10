package io.github.christiancg.lwnrdb.client.it;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.time.Duration;

final class ItSettings {
    private ItSettings() {
    }

    static ClientSettings.Builder of(ServerProcess server) {
        return ClientSettings.builder().host("localhost", server.port())
                .credentials(ServerProcess.ADMIN, ServerProcess.PASSWORD).requestTimeout(Duration.ofSeconds(60));
    }
}
