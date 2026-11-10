package io.github.christiancg.lwnrdb.spring.boot.it;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;
import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;

@LwnrdbEntity(collection = "orders")
public record Order(@Id String orderId, String status, double total, LocalDateTime placedAt, GeoPoint location,
        Vector embedding, @Transient String note) {
    public static Order of(String id, String status, double total) {
        return new Order(id, status, total, LocalDateTime.of(2024, 1, 1, 10, 0), GeoPoint.of(40.71, -74.0),
                Vector.of(1, 0), null);
    }

    public Order withNote(String text) {
        return new Order(orderId, status, total, placedAt, location, embedding, text);
    }
}
