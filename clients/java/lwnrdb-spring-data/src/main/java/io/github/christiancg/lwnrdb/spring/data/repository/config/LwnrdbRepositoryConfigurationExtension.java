package io.github.christiancg.lwnrdb.spring.data.repository.config;

import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;
import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import io.github.christiancg.lwnrdb.spring.data.repository.support.LwnrdbRepositoryFactoryBean;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.config.RepositoryConfigurationSource;

public class LwnrdbRepositoryConfigurationExtension extends RepositoryConfigurationExtensionSupport {
    public static final String DEFAULT_TEMPLATE_REF = "lwnrdbTemplate";
    private static final String TEMPLATE_REF = "templateRef";

    @Override
    public String getModuleName() {
        return "LWNRDB";
    }

    @Override
    protected String getModulePrefix() {
        return "lwnrdb";
    }

    @Override
    public String getRepositoryFactoryBeanClassName() {
        return LwnrdbRepositoryFactoryBean.class.getName();
    }

    @Override
    protected Collection<Class<? extends Annotation>> getIdentifyingAnnotations() {
        return List.of(LwnrdbEntity.class);
    }

    @Override
    protected Collection<Class<?>> getIdentifyingTypes() {
        return List.of(LwnrdbRepository.class);
    }

    @Override
    public void postProcess(BeanDefinitionBuilder builder, RepositoryConfigurationSource source) {
        builder.addPropertyReference("lwnrdbOperations",
                source.getAttribute(TEMPLATE_REF).orElse(DEFAULT_TEMPLATE_REF));
    }
}
