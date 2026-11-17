package io.github.christiancg.lwnrdb.spring.data.model;

import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;
import org.springframework.data.annotation.Id;

public interface Invalid {
    record NumericId(@Id long key) {
    }

    record TwoIds(@Id String key, String id) {
    }

    record NoId(String name) {
    }

    @LwnrdbEntity(collection = "x", database = "shop")
    record ShortCollection(String id) {
    }

    record NoDatabase(String id) {
    }
}
