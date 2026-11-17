package io.github.christiancg.lwnrdb.spring.data.model;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

@SuppressWarnings("UnusedReturnValue")
public interface WidgetRepository extends LwnrdbRepository<Widget, String> {
    List<Widget> findByName(String name);

    List<Widget> findByNameNot(String name);

    List<Widget> findByNameIsNull();

    List<Widget> findByNameIsNotNull();

    List<Widget> findBySizeGreaterThan(double size);

    List<Widget> findBySizeGreaterThanEqual(double size);

    List<Widget> findBySizeLessThan(double size);

    List<Widget> findBySizeLessThanEqual(double size);

    List<Widget> findBySizeBetween(double low, double high);

    List<Widget> findByNameIn(Collection<String> names);

    List<Widget> findByNameIn(String[] names);

    List<Widget> findByNameNotIn(List<String> names);

    List<Widget> findByNameContaining(String text);

    List<Widget> findByTitleTrue();

    List<Widget> findByTitleFalse();

    List<Widget> findBySizeAfter(double size);

    List<Widget> findBySizeBefore(double size);

    List<Widget> findByAddressCity(String city);

    List<Widget> findByAddressCityWithin(List<GeoPoint> polygon);

    List<Widget> findByNameAndSizeGreaterThanOrTitle(String name, double size, String title);

    List<Widget> findByNameIgnoreCase(String name);

    Optional<Widget> findOneByName(String name);

    Widget findFirstByNameOrderBySizeDesc(String name);

    List<Widget> findTop3ByName(String name);

    List<Widget> findByName(String name, Sort sort);

    List<Widget> findByName(String name, Limit limit);

    List<Widget> findByName(String name, Pageable pageable);

    Page<Widget> findPageByName(String name, Pageable pageable);

    Slice<Widget> findSliceByName(String name, Pageable pageable);

    Stream<Widget> streamByName(String name);

    long countByName(String name);

    boolean existsByName(String name);

    long deleteByName(String name);

    List<Widget> removeByName(String name);
}
