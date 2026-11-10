package io.github.christiancg.lwnrdb.spring.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.christiancg.lwnrdb.spring.data.Stack;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbTemplate;
import io.github.christiancg.lwnrdb.spring.data.model.WidgetRepository;
import io.github.christiancg.lwnrdb.spring.data.repository.config.LwnrdbRepositoryConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

public class EnableLwnrdbRepositoriesTest {
    @Configuration
    @EnableLwnrdbRepositories(basePackageClasses = WidgetRepository.class)
    static class Config {
    }

    @Test
    public void test_annotated_configuration_registers_the_repositories() throws Exception {
        try (var stack = new Stack(); var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LwnrdbRepositoryConfigurationExtension.DEFAULT_TEMPLATE_REF, LwnrdbTemplate.class,
                    () -> stack.template);
            context.register(Config.class);
            context.refresh();
            assertNotNull(context.getBean(WidgetRepository.class));
            assertEquals("LWNRDB", new LwnrdbRepositoryConfigurationExtension().getModuleName());
        }
    }
}
