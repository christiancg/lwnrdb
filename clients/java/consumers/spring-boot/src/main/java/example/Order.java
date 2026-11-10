package example;

import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbEntity;
import java.time.LocalDateTime;

@LwnrdbEntity(collection = "orders")
public record Order(String id, String status, double total, LocalDateTime placedAt) {
}
