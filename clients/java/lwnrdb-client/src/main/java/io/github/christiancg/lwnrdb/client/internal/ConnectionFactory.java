package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.ServerAddress;
import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionFactory {
    private final ClientSettings settings;
    private final Json json;
    private final RequestEncoder encoder;
    private final TlsSocketFactory sockets;
    private final List<ServerAddress> hosts;
    private final AtomicInteger nextHost = new AtomicInteger();

    public ConnectionFactory(ClientSettings settings, Json json, RequestEncoder encoder) {
        this.settings = settings;
        this.json = json;
        this.encoder = encoder;
        this.sockets = new TlsSocketFactory(settings.tls(), settings.connectTimeout());
        this.hosts = settings.hosts();
    }

    public WireConnection open() {
        ConnectionLostException last = null;
        var remaining = hosts.size();
        while (remaining-- > 0) {
            final var address = hosts.get(Math.floorMod(nextHost.getAndIncrement(), hosts.size()));
            try {
                return authenticated(new WireConnection(sockets.open(address), json, settings.maxReplyBytes()));
            } catch (IOException e) {
                last = new ConnectionLostException("could not connect to " + address + ": " + e.getMessage(), e);
            }
        }
        throw last;
    }

    public ClientSettings settings() {
        return settings;
    }

    private WireConnection authenticated(WireConnection connection) {
        final var username = settings.username();
        if (username.isEmpty()) {
            return connection;
        }
        final var password = settings.password();
        try {
            ResponseDecoder.check(connection.request(encoder.authenticate(username.get(), new String(password)),
                    settings.requestTimeout()));
            return connection;
        } catch (LwnrdbException e) {
            connection.close();
            throw e;
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
