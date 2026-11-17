package io.github.christiancg.lwnrdb.spring.data.model;

import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import java.util.List;

@SuppressWarnings("unused")
public interface InvalidRepositories {
    interface LikeRepository extends LwnrdbRepository<Widget, String> {
        List<Widget> findByNameLike(String pattern);
    }

    interface TwoOrdersRepository extends LwnrdbRepository<Widget, String> {
        List<Widget> findByNameOrderBySizeAscNameDesc(String name);
    }

    interface IdIgnoreCaseRepository extends LwnrdbRepository<Widget, String> {
        List<Widget> findByCodeIgnoreCase(String code);
    }

    interface TransientRepository extends LwnrdbRepository<Widget, String> {
        List<Widget> findByScratch(String value);
    }

    interface NumericIdRepository extends LwnrdbRepository<Widget, Long> {
    }
}
