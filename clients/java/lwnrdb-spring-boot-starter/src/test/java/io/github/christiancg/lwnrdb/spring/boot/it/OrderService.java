package io.github.christiancg.lwnrdb.spring.boot.it;

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
    public long saveBothAndCount(Order first, Order second) {
        orders.save(first);
        orders.save(second);
        return orders.findAllById(List.of(first.orderId(), second.orderId())).size();
    }

    @Transactional
    public void saveThenFail(Order order) {
        orders.save(order);
        throw new IllegalStateException("rolled back on purpose");
    }

    @Transactional
    public void saveAndWait(Order order, long millis) throws InterruptedException {
        orders.save(order);
        Thread.sleep(millis);
        orders.save(order.withNote("again"));
    }
}
