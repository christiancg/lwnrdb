package io.github.christiancg.lwnrdb.spring.data;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.RetryPolicy;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbTemplate;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbMappingContext;
import io.github.christiancg.lwnrdb.spring.data.repository.support.LwnrdbRepositoryFactory;
import io.github.christiancg.lwnrdb.testsupport.CannedReplies;
import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.io.IOException;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

public final class Stack implements AutoCloseable {
    public final CannedReplies replies = new CannedReplies();
    public final FakeServer server;
    public final LwnrdbClient client;
    public final LwnrdbTemplate template;

    public Stack() throws IOException {
        this(8);
    }

    public Stack(int poolSize) throws IOException {
        this.server = new FakeServer(replies);
        this.client = LwnrdbClient.connect(ClientSettings.builder().host("127.0.0.1", server.port()).poolSize(poolSize)
                .acquireTimeout(Duration.ofMillis(300)).requestTimeout(Duration.ofSeconds(5))
                .retryPolicy(RetryPolicy.none()).build());
        this.template = new LwnrdbTemplate(client, new LwnrdbMappingContext("shop"));
    }

    public <R> R repository(Class<R> type) {
        return new LwnrdbRepositoryFactory(template).getRepository(type);
    }

    public ObjectNode next(String type) throws InterruptedException {
        return server.nextRequestOfType(type);
    }

    public String steps(String type) throws InterruptedException {
        return next(type).get("aggregationSteps").toString();
    }

    public Stack results(String rowsJson) {
        replies.reply("AGGREGATE", "{\"type\":\"AGGREGATE\",\"status\":\"OK\",\"results\":" + rowsJson + "}");
        return this;
    }

    public Stack count(long count) {
        return results("[{\"count\":" + count + "}]");
    }

    @Override
    public void close() throws IOException {
        client.close();
        server.close();
    }
}
