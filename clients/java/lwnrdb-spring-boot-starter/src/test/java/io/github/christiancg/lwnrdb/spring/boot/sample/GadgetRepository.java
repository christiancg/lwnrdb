package io.github.christiancg.lwnrdb.spring.boot.sample;

import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;

public interface GadgetRepository extends LwnrdbRepository<Gadget, String> {
}
