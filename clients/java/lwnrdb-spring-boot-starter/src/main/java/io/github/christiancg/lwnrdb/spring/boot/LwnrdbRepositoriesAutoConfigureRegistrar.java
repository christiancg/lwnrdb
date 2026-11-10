package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.spring.data.repository.EnableLwnrdbRepositories;
import io.github.christiancg.lwnrdb.spring.data.repository.config.LwnrdbRepositoryConfigurationExtension;
import java.lang.annotation.Annotation;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.data.AbstractRepositoryConfigurationSourceSupport;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;

@NullMarked
class LwnrdbRepositoriesAutoConfigureRegistrar extends AbstractRepositoryConfigurationSourceSupport {
    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableLwnrdbRepositories.class;
    }

    @Override
    protected Class<?> getConfiguration() {
        return EnableLwnrdbRepositoriesConfiguration.class;
    }

    @Override
    protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
        return new LwnrdbRepositoryConfigurationExtension();
    }

    @EnableLwnrdbRepositories
    private static final class EnableLwnrdbRepositoriesConfiguration {
    }
}
