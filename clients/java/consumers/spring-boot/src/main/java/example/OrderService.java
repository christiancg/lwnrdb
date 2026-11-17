package example;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional
    public void replace(Order cancelled, Order placed) {
        orders.deleteById(cancelled.id());
        orders.save(placed);
    }

    public List<Order> largeActiveOrders() {
        return orders.findByStatusAndTotalGreaterThanOrderByTotalDesc("active", 100);
    }
}
