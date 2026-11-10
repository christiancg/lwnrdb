package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;

final class Wire {
    static final Json JSON = new Json(4096);

    private Wire() {
    }

    static WireConnection connect(FakeServer server) throws IOException {
        return new WireConnection(new Socket(InetAddress.getLoopbackAddress(), server.port()), JSON, 4096);
    }

    static ClientSettings.Builder settings(FakeServer server) {
        return ClientSettings.builder().host("127.0.0.1", server.port()).requestTimeout(Duration.ofSeconds(5))
                .acquireTimeout(Duration.ofMillis(300)).shutdownTimeout(Duration.ofSeconds(2));
    }

    static ConnectionFactory factory(ClientSettings settings) {
        final var json = new Json(settings);
        return new ConnectionFactory(settings, json, new RequestEncoder(json));
    }

    static tools.jackson.databind.node.ObjectNode request(String type) {
        return FakeServer.MAPPER.createObjectNode().put("type", type);
    }
}
