package io.github.christiancg.lwnrdb.spring.boot.it;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

final class SpringServer {
    static final String DATABASE = "spring_it";
    static final String ORDERS = "orders";

    private SpringServer() {
    }

    static void createOrders(ServerProcess server) {
        try (var client = LwnrdbClient.connect(ClientSettings.builder().host("localhost", server.port())
                .credentials(ServerProcess.ADMIN, ServerProcess.PASSWORD).build())) {
            if (!client.listDatabases().contains(DATABASE)) {
                client.database(DATABASE).create();
            }
            if (!client.database(DATABASE).listCollections().contains(ORDERS)) {
                client.database(DATABASE).collection(ORDERS).create();
            }
        }
    }

    static ConfigurableApplicationContext start(ServerProcess server, Map<String, String> extra) {
        final var properties = new LinkedHashMap<String, String>();
        properties.put("lwnrdb.hosts", "localhost:" + server.port());
        properties.put("lwnrdb.username", ServerProcess.ADMIN);
        properties.put("lwnrdb.password", ServerProcess.PASSWORD);
        properties.put("lwnrdb.database", DATABASE);
        properties.put("spring.main.banner-mode", "off");
        properties.putAll(extra);
        final var arguments = new ArrayList<String>();
        properties.forEach((key, value) -> arguments.add("--" + key + "=" + value));
        final var application = new SpringApplication(ItApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(arguments.toArray(String[]::new));
    }
}
