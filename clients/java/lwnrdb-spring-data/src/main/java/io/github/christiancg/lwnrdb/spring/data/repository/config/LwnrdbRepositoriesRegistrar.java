package io.github.christiancg.lwnrdb.spring.data.repository.config;

import io.github.christiancg.lwnrdb.spring.data.repository.EnableLwnrdbRepositories;
import java.lang.annotation.Annotation;
import org.springframework.data.repository.config.RepositoryBeanDefinitionRegistrarSupport;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;

public class LwnrdbRepositoriesRegistrar extends RepositoryBeanDefinitionRegistrarSupport {
    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableLwnrdbRepositories.class;
    }

    @Override
    protected RepositoryConfigurationExtension getExtension() {
        return new LwnrdbRepositoryConfigurationExtension();
    }
}
