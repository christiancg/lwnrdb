Stores a `double[]` property as a `#vector(...)` value:

```java
public record Article(String id, @LwnrdbVector double[] embedding) {
}
```
