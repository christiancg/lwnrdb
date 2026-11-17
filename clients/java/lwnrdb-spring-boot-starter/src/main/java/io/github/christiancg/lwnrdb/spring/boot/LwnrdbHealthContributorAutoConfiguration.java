package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = LwnrdbAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(LwnrdbClient.class)
@ConditionalOnEnabledHealthIndicator("lwnrdb")
public class LwnrdbHealthContributorAutoConfiguration {
    @Bean(name = "lwnrdbHealthIndicator")
    @ConditionalOnMissingBean(name = "lwnrdbHealthIndicator")
    public LwnrdbHealthIndicator lwnrdbHealthIndicator(LwnrdbClient client) {
        return new LwnrdbHealthIndicator(client);
    }
}
