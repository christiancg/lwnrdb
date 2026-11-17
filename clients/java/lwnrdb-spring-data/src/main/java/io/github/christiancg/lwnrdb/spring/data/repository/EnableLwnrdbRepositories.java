package io.github.christiancg.lwnrdb.spring.data.repository;

import io.github.christiancg.lwnrdb.spring.data.repository.config.LwnrdbRepositoriesRegistrar;
import io.github.christiancg.lwnrdb.spring.data.repository.support.LwnrdbRepositoryFactoryBean;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Import;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.config.DefaultRepositoryBaseClass;
import org.springframework.data.repository.query.QueryLookupStrategy.Key;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(LwnrdbRepositoriesRegistrar.class)
@SuppressWarnings("unused")
public @interface EnableLwnrdbRepositories {
    String[] value() default {};

    String[] basePackages() default {};

    Class<?>[] basePackageClasses() default {};

    Filter[] includeFilters() default {};

    Filter[] excludeFilters() default {};

    String repositoryImplementationPostfix() default "Impl";

    String namedQueriesLocation() default "";

    Key queryLookupStrategy() default Key.CREATE_IF_NOT_FOUND;

    Class<?> repositoryFactoryBeanClass() default LwnrdbRepositoryFactoryBean.class;

    Class<?> repositoryBaseClass() default DefaultRepositoryBaseClass.class;

    Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

    boolean considerNestedRepositories() default false;

    BootstrapMode bootstrapMode() default BootstrapMode.DEFAULT;

    String templateRef() default "lwnrdbTemplate";
}
