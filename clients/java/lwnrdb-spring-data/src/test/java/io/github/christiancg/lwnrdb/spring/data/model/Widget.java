package io.github.christiancg.lwnrdb.spring.data.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;

@LwnrdbEntity(collection = "widgets", database = "shop")
public record Widget(@Id String code, String name, double size, @JsonProperty("label") String title, Address address,
        @Transient String scratch) {
    public static Widget of(String code, String name, double size) {
        return new Widget(code, name, size, "t", new Address("Lima"), null);
    }
}
