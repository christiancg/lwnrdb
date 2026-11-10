package io.github.christiancg.lwnrdb.spring.boot.it;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

public interface OrderRepository extends LwnrdbRepository<Order, String> {
    List<Order> findByStatus(String status);

    List<Order> findByStatusAndTotalGreaterThan(String status, double total);

    List<Order> findByStatusOrTotalLessThan(String status, double total);

    List<Order> findByStatusNot(String status);

    List<Order> findByTotalGreaterThanEqual(double total);

    List<Order> findByTotalLessThanEqual(double total);

    List<Order> findByTotalBetween(double low, double high);

    List<Order> findByStatusIn(Collection<String> statuses);

    List<Order> findByStatusNotIn(Collection<String> statuses);

    List<Order> findByStatusContaining(String text);

    List<Order> findByPlacedAtAfter(LocalDateTime moment);

    List<Order> findByPlacedAtBefore(LocalDateTime moment);

    List<Order> findByLocationWithin(List<GeoPoint> polygon);

    List<Order> findByStatusIsNull();

    Optional<Order> findByOrderId(String id);

    Order findFirstByStatusOrderByTotalDesc(String status);

    List<Order> findTop2ByOrderByTotalAsc();

    List<Order> findByStatus(String status, Sort sort);

    List<Order> findByStatus(String status, Limit limit);

    Page<Order> findByStatus(String status, Pageable pageable);

    Slice<Order> findSliceByStatus(String status, Pageable pageable);

    Stream<Order> streamByStatus(String status);

    long countByStatus(String status);

    boolean existsByStatus(String status);

    long deleteByStatus(String status);
}
