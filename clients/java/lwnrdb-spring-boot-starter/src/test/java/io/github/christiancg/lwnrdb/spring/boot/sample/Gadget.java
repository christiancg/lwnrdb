package io.github.christiancg.lwnrdb.spring.boot.sample;

import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;

@LwnrdbEntity(collection = "gadgets")
public record Gadget(String id, String name) {
}
