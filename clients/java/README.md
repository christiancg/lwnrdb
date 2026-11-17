# LWNRDB Java client

The Java client for [LWNRDB](../../README.md). It keeps a pool of authenticated connections and gives you a fluent
query builder, transactions, live queries and mapping to your own types. It needs Java 17 or newer, and its only
runtime dependency is Jackson 3, which can live next to an application's Jackson 2.

## Installation

Import the BOM and add the client.

Maven:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.christiancg</groupId>
            <artifactId>lwnrdb-client-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.christiancg</groupId>
        <artifactId>lwnrdb-client</artifactId>
    </dependency>
</dependencies>
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("io.github.christiancg:lwnrdb-client-bom:0.1.0"))
    implementation("io.github.christiancg:lwnrdb-client")
}
```

## Quick start

```java
ClientSettings settings = ClientSettings.builder()
        .host("localhost", 8989)
        .credentials("admin", password)
        .build();

try (LwnrdbClient client = LwnrdbClient.connect(settings)) {
    Database shop = client.database("shop");
    shop.create();
    Collection<Document> orders = shop.collection("orders");
    orders.create();

    orders.save(Document.of("_id", "o-1", "status", "active", "total", 120));
    orders.findById("o-1").ifPresent(order -> System.out.println(order.getString("status").orElse("")));

    List<Document> page = orders.query()
            .filter(and(field("status").eq("active"), field("total").gt(100)))
            .sort("total", Order.DESC)
            .limit(10)
            .toList();
}
```

A collection has to exist before you write to it. Besides `save` and `findById`, a collection has `insert`, `saveAll`,
`delete`, `updateWhere` and `deleteWhere`. `LwnrdbClient` is thread-safe, and the pool size is how many requests run at
once.

## Settings

Everything is on `ClientSettings.builder()`. The ones you usually set are `host(host, port)` (call it again to list
more servers), `credentials(username, password)`, `tls(TlsSettings)` and `poolSize` (8 by default). Timeouts, the
retry policy and the rest are in the API reference.

## Typed collections

`Database.collection(name, Class)` maps documents to records or POJOs through Jackson. A property named `id` is stored
as `_id`.

```java
public record Order(String id, String status, double total, LocalDateTime placedAt) {
}

Collection<Order> orders = shop.collection("orders", Order.class);
orders.save(new Order("o-1", "active", 120, LocalDateTime.now()));
List<Order> active = orders.query().filter(field("status").eq("active")).toList();
```

To map without Jackson, implement `DocumentCodec<T>`.

## Custom value types

The server has four value types of its own. The client maps them to Java types both ways.

| Server value | Java type | Example |
|---|---|---|
| `#datetime(2024-01-01T10:00)` | `java.time.LocalDateTime` | `field("placedAt").gte(LocalDateTime.of(2024, 1, 1, 0, 0))` |
| `#time(09:30)` | `java.time.LocalTime` | `field("opensAt").lt(LocalTime.NOON)` |
| `#geo(lat,lng)` | `GeoPoint` | `distance("location", GeoPoint.of(40.71, -74.0), GeoDistanceComparator.SMALLER_THAN, 1_000)` |
| `#vector(v0,v1,...)` | `Vector` | `nearest("embedding", Vector.of(0.12, 0.44, 0.91), 10)` |

The server's date-time has no zone, so an `Instant` or a zoned date-time is refused unless you set `temporalZone`.
`nearest` is approximate. Add `.exact()` to get the true top `k`.

## Queries

`Collection.query()` starts a pipeline. Queries are immutable, so every step returns a new one.

| Step | Method |
|---|---|
| `FILTER` | `filter(Filter)` with `field(...).eq/ne/gt/gte/lt/lte/in/notIn/contains`, `and/or/nor/xor/nand`, `distance`, `within`, `nearest`, `script` |
| `MAP` | `map(MapOps.set(field, MidOps...), MapOps.remove(field))`, each optionally `.when(filter)` |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `groupBy(field)`, `distinct()`, `distinct(field)`, `join(collection, localField, remoteField, asField)` |
| `SORT`, `SKIP`, `LIMIT` | `sort(field, Order)`, `skip(n)`, `limit(n)` |
| `REDUCE` | `reduce(script)`, `reduce(script, initialValue, resultField)` |
| `COUNT` | `count()` |

Run it with `toList()`, `toDocuments()`, `first()`, `count()` or `analyze()`.

## Transactions

```java
String id = client.transaction(tx -> {
    TransactionCollection<Document> accounts = tx.collection("bank", "accounts");
    accounts.save(debit);
    accounts.save(credit);
    return tx.id();
});
```

The body commits when it returns and rolls back when it throws. A transaction holds one connection, its writes lock
their collections until the commit, and the server rolls it back after `transactionIdleTimeoutMs` idle.

## Live queries

```java
Subscription subscription = orders.listen(
        orders.query().filter(field("status").eq("pending")),
        update -> render(update.results()));
...
subscription.close();
```

The handler gets the whole result right away, then again every time a write changes it. Live queries use their own
connections, not the pool.

## Errors and retries

Every refusal from the server is a `LwnrdbException` with the server's error code. There is a subclass per range:
`AuthenticationException` (401), `ForbiddenException` (403), `NotFoundException` (404), `ConflictException` (409),
`ListenEndedException` (410), `UnavailableException` (503) and `UnknownOutcomeException` (`500-33`, `500-34`).

The client retries [retryable](../../docs/protocol.md#error-codes) codes and reads that lost their connection. It
never retries a write that lost its connection (`ConnectionLostException`), an unknown outcome, or anything inside a
transaction.

## TLS

```java
ClientSettings.builder()
        .host("db.example.com", 8989)
        .tls(TlsSettings.trustStore(Path.of("lwnrdb-ca.p12"), storePassword))
        ...
```

`TlsSettings.systemTrust()` uses the JVM's certificate authorities. `TlsSettings.insecure()` trusts any certificate and
is only for development.

## Spring Data and Spring Boot

`lwnrdb-spring-data` adds Spring Data repositories, a template and a transaction manager on top of the client, and
`lwnrdb-spring-boot-starter` configures all of it from `lwnrdb.*` properties. Both need Spring Boot 4. Import the BOM as
above and add the starter:

```xml
<dependency>
    <groupId>io.github.christiancg</groupId>
    <artifactId>lwnrdb-spring-boot-starter</artifactId>
</dependency>
```

```properties
lwnrdb.hosts=db1.example.com:8989,db2.example.com:8989
lwnrdb.username=app
lwnrdb.password=${LWNRDB_PASSWORD}
lwnrdb.database=shop
```

`lwnrdb.database` is the database of an entity that names none. The client's other settings have properties of the
same name in kebab case (`lwnrdb.pool-size`, `lwnrdb.tls.*`, `lwnrdb.retry.*`). With the actuator on the classpath,
`/actuator/health` includes `lwnrdb`.

### Entities

```java
@LwnrdbEntity(collection = "orders")
public record Order(String id, String status, double total, LocalDateTime placedAt) {
}
```

`@LwnrdbEntity` is optional. The id is the property annotated with `@Id` or named `id`, a `String` stored as `_id`,
and a `null` id gets a random UUID on save.

### Repositories

```java
public interface OrderRepository extends LwnrdbRepository<Order, String> {
    List<Order> findByStatusAndTotalGreaterThanOrderByTotalDesc(String status, double total);

    Page<Order> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);
}
```

The starter enables the repositories in the application's packages. Anywhere else, use `@EnableLwnrdbRepositories`.

| Keyword | Filter |
|---|---|
| (none), `Is`, `Equals`, `Not` | `EQUALS`, `NOT_EQUALS` |
| `IsNull`, `IsNotNull` | `EQUALS null`, `NOT_EQUALS null` |
| `True`, `False` | `EQUALS true`, `EQUALS false` |
| `GreaterThan`, `GreaterThanEqual`, `LessThan`, `LessThanEqual`, `After`, `Before`, `Between` | the range operators (`Between` is inclusive) |
| `In`, `NotIn` | `IN`, `NOT_IN` |
| `Containing` | `CONTAINS` |
| `Within` (a list of `GeoPoint`) | geo `within` |
| `And`, `Or` | `AND`, `OR` |

A keyword the server can't run, such as `Like` or `Regex`, is refused when the repository is created.

### Transactions

The starter registers a `LwnrdbTransactionManager`, so `@Transactional` works:

```java
@Transactional
public void replace(Order cancelled, Order placed) {
    orders.deleteById(cancelled.id());
    orders.save(placed);
}
```

A Spring transaction is one server transaction on one connection. `NESTED` is refused, since the server has no
savepoints. Server errors become Spring `DataAccessException`s.

### The template

`LwnrdbTemplate` has what the repositories use, plus `query(Order.class)`, which gives you the client's `Query<Order>`
for what derived queries can't express, and `execute(client -> ...)` for anything else.

## API reference

The API docs are in [`apidocs/`](apidocs) and in the published Javadoc jar.

## Building

```bash
mvn -f clients/java/pom.xml verify
mvn package -DskipTests                                # at the repository root, the server jar the integration tests use
mvn -f clients/java/pom.xml verify -Pintegration -Dlwnrdb.jar=$PWD/target/lwnrdb.jar
mvn -f clients/java/pom.xml -P javadoc package
```

## Releasing

Push a `java-client-vX.Y.Z` tag. The release workflow tests the client and deploys it to Maven Central.
