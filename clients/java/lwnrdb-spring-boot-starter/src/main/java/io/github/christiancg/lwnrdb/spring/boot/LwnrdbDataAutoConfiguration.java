package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbTemplate;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbMappingContext;
import io.github.christiancg.lwnrdb.spring.data.transaction.LwnrdbTransactionManager;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.ClassUtils;

@AutoConfiguration(after = LwnrdbAutoConfiguration.class)
@ConditionalOnClass(LwnrdbTemplate.class)
@ConditionalOnBean(LwnrdbClient.class)
@EnableTransactionManagement
public class LwnrdbDataAutoConfiguration {
    public static final String TEMPLATE_BEAN = "lwnrdbTemplate";
    public static final String TRANSACTION_MANAGER_BEAN = "lwnrdbTransactionManager";

    @Bean
    @ConditionalOnMissingBean
    public LwnrdbMappingContext lwnrdbMappingContext(LwnrdbProperties properties, BeanFactory beanFactory) {
        final var context = new LwnrdbMappingContext(properties.database());
        context.setInitialEntitySet(scanEntities(beanFactory));
        return context;
    }

    @Bean(name = TEMPLATE_BEAN)
    @ConditionalOnMissingBean(LwnrdbOperations.class)
    public LwnrdbTemplate lwnrdbTemplate(LwnrdbClient client, LwnrdbMappingContext mappingContext) {
        return new LwnrdbTemplate(client, mappingContext);
    }

    @Bean(name = TRANSACTION_MANAGER_BEAN)
    @ConditionalOnMissingBean(name = TRANSACTION_MANAGER_BEAN)
    public LwnrdbTransactionManager lwnrdbTransactionManager(LwnrdbClient client) {
        return new LwnrdbTransactionManager(client);
    }

    static Set<Class<?>> scanEntities(BeanFactory beanFactory) {
        final var entities = new HashSet<Class<?>>();
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return entities;
        }
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(LwnrdbEntity.class));
        for (final var basePackage : AutoConfigurationPackages.get(beanFactory)) {
            for (final var candidate : scanner.findCandidateComponents(basePackage)) {
                entities.add(ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()),
                        ClassUtils.getDefaultClassLoader()));
            }
        }
        return entities;
    }
}
