The repository interface to extend: CRUD, paging and sorting, plus derived query methods.

```java
public interface OrderRepository extends LwnrdbRepository<Order, String> {
    List<Order> findByStatusAndTotalGreaterThan(String status, double total);
}
```

Ids are strings. Derived queries support equality, `Not`, comparisons, `Between`, `In`, `NotIn`, `Containing`,
`IsNull`, `IsNotNull`, `True`, `False`, `After`, `Before` and geo `Within`; `First`/`Top`, `Limit`, `Sort`, `Pageable`;
and the `count`, `exists` and `delete` prefixes. `Like`, `StartingWith`, `EndingWith`, `Regex`, `Near` and a sort on
more than one property are refused when the repository is created.

@param <T> the entity type
@param <I> the id type, always `String`
