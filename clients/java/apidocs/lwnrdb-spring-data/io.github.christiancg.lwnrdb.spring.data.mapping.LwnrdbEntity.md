Marks a class stored in LWNRDB and names where. Optional: an unannotated class is stored in the
collection named after the class (`Order` becomes `order`) in the default database.

```java
@LwnrdbEntity(collection = "orders")
public record Order(@Id String orderId, String status, double total) {
}
```

## collection()

@return the collection, or empty for the uncapitalised class name

## database()

@return the database, or empty for the default database (`lwnrdb.database`)
