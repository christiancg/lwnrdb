package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import io.github.christiancg.lwnrdb.spring.data.repository.support.LwnrdbRepositoryFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.ConditionalOnRepositoryType;
import org.springframework.boot.autoconfigure.data.RepositoryType;
import org.springframework.context.annotation.Import;

@AutoConfiguration(after = LwnrdbDataAutoConfiguration.class)
@ConditionalOnClass(LwnrdbRepository.class)
@ConditionalOnBean(LwnrdbOperations.class)
@ConditionalOnMissingBean(LwnrdbRepositoryFactoryBean.class)
@ConditionalOnRepositoryType(store = "lwnrdb", type = RepositoryType.IMPERATIVE)
@Import(LwnrdbRepositoriesAutoConfigureRegistrar.class)
public class LwnrdbRepositoriesAutoConfiguration {
}
