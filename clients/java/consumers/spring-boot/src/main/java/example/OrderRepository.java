package example;

import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import java.util.List;

public interface OrderRepository extends LwnrdbRepository<Order, String> {
    List<Order> findByStatusAndTotalGreaterThanOrderByTotalDesc(String status, double total);
}
