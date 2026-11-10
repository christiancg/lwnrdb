package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbExceptionTranslator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnClass(LwnrdbClient.class)
@EnableConfigurationProperties(LwnrdbProperties.class)
public class LwnrdbAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ClientSettings lwnrdbClientSettings(LwnrdbProperties properties, ObjectProvider<JsonMapper> objectMapper,
            ObjectProvider<LwnrdbClientSettingsCustomizer> customizers) {
        final var builder = properties.toSettings(objectMapper.getIfUnique());
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public LwnrdbClient lwnrdbClient(ClientSettings settings) {
        try {
            return LwnrdbClient.connect(settings);
        } catch (LwnrdbException e) {
            throw new LwnrdbExceptionTranslator().translate(e);
        }
    }
}
