package io.github.christiancg.lwnrdb.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.spring.boot.sample.Gadget;
import io.github.christiancg.lwnrdb.spring.boot.sample.GadgetRepository;
import io.github.christiancg.lwnrdb.spring.boot.sample.SampleConfiguration;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbTemplate;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbMappingContext;
import io.github.christiancg.lwnrdb.spring.data.transaction.LwnrdbTransactionManager;
import io.github.christiancg.lwnrdb.testsupport.CannedReplies;
import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

public class LwnrdbAutoConfigurationTest {
    private final CannedReplies replies = new CannedReplies();
    private FakeServer server;
    private ApplicationContextRunner runner;

    @BeforeEach
    public void start() throws Exception {
        server = new FakeServer(replies);
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LwnrdbAutoConfiguration.class,
                        LwnrdbDataAutoConfiguration.class, LwnrdbRepositoriesAutoConfiguration.class,
                        LwnrdbHealthContributorAutoConfiguration.class))
                .withUserConfiguration(SampleConfiguration.class).withPropertyValues(
                        "lwnrdb.hosts=127.0.0.1:" + server.port(), "lwnrdb.database=shop", "lwnrdb.request-timeout=5s");
    }

    @AfterEach
    public void stop() throws Exception {
        server.close();
    }

    @Configuration
    static class UserSettings {
        @Bean
        ClientSettings custom(LwnrdbProperties properties) {
            return ClientSettings.builder().host("127.0.0.1", Integer.parseInt(properties.hosts().get(0).split(":")[1]))
                    .poolSize(2).build();
        }
    }

    @Configuration
    static class Customized {
        @Bean
        LwnrdbClientSettingsCustomizer poolOfThree() {
            return builder -> builder.poolSize(3);
        }

        @Bean
        JsonMapper bootMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Test
    public void test_the_whole_stack_is_configured() {
        runner.run(context -> {
            assertNotNull(context.getBean(LwnrdbClient.class));
            assertNotNull(context.getBean(LwnrdbTemplate.class));
            assertNotNull(context.getBean(LwnrdbTransactionManager.class));
            assertNotNull(context.getBean(GadgetRepository.class));
            assertTrue(context.getBean(LwnrdbMappingContext.class).hasPersistentEntityFor(Gadget.class));
            assertEquals("shop", context.getBean(LwnrdbMappingContext.class).defaultDatabase());
            assertEquals(Duration.ofSeconds(5), context.getBean(ClientSettings.class).requestTimeout());
        });
    }

    @Test
    public void test_user_beans_and_customizers_win() {
        runner.withUserConfiguration(UserSettings.class)
                .run(context -> assertEquals(2, context.getBean(ClientSettings.class).poolSize()));
        runner.withUserConfiguration(Customized.class).run(context -> {
            final var settings = context.getBean(ClientSettings.class);
            assertEquals(3, settings.poolSize());
            assertTrue(settings.objectMapper().isPresent());
        });
    }

    @Test
    public void test_repositories_can_be_turned_off() {
        runner.withPropertyValues("spring.data.lwnrdb.repositories.type=none")
                .run(context -> assertFalse(context.containsBean("gadgetRepository")));
    }

    @Test
    public void test_the_health_indicator_follows_the_server_state() {
        replies.reply("SERVER_INFO", "{\"type\":\"SERVER_INFO\",\"status\":\"OK\",\"server\":{\"state\":\"READY\","
                + "\"version\":\"1.0\"}}");
        runner.run(context -> {
            final var health = health(context, true);
            assertEquals(Status.UP, health.getStatus());
            assertEquals("1.0", health.getDetails().get("version"));
        });
        replies.reply("SERVER_INFO", "{\"type\":\"SERVER_INFO\",\"status\":\"OK\",\"server\":{\"state\":\"SYNCING\"}}");
        runner.run(context -> assertEquals(Status.OUT_OF_SERVICE, health(context, false).getStatus()));
        replies.reply("SERVER_INFO", "{\"type\":\"SERVER_INFO\",\"status\":\"ERROR\",\"errorCode\":\"500-35\"}");
        runner.run(context -> assertEquals(Status.DOWN, health(context, false).getStatus()));
        runner.withPropertyValues("management.health.lwnrdb.enabled=false")
                .run(context -> assertFalse(context.containsBean("lwnrdbHealthIndicator")));
    }

    @Test
    public void test_a_refused_login_fails_with_a_spring_exception() {
        replies.reply("AUTHENTICATE", "{\"type\":\"AUTHENTICATE\",\"status\":\"ERROR\",\"errorCode\":\"401-3\"}");
        runner.withPropertyValues("lwnrdb.username=alice", "lwnrdb.password=wrong").run(context -> {
            Throwable cause = Objects.requireNonNull(context.getStartupFailure());
            while (cause.getCause() != null && !(cause instanceof DataAccessResourceFailureException)) {
                cause = cause.getCause();
            }
            assertInstanceOf(DataAccessResourceFailureException.class, cause, cause.toString());
        });
    }

    @Test
    public void test_address_parsing() {
        assertEquals(8989, LwnrdbProperties.address("host").port());
        assertEquals("h", LwnrdbProperties.address(" h : 1 ").host());
        assertTrue(LwnrdbDataAutoConfiguration
                .scanEntities(new org.springframework.beans.factory.support.DefaultListableBeanFactory()).isEmpty());
    }

    private static Health health(ApplicationContext context, boolean includeDetails) {
        return Objects.requireNonNull(context.getBean(LwnrdbHealthIndicator.class).health(includeDetails));
    }
}
