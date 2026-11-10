# LWNRDB Java client

The official Java client for [LWNRDB](../../README.md): a pool of authenticated connections, a fluent query builder,
transactions, live queries and POJO mapping, on Java 17 or newer. Its one runtime dependency is Jackson 3
(`tools.jackson.core:jackson-databind`), which coexists with an application's Jackson 2.

## Installation

Import the BOM and add the client.

**Maven**

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

**Gradle (Kotlin DSL)**

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

A collection must exist before its first write: a save into a missing one is refused with `404-11`.

`LwnrdbClient` is thread-safe and holds a pool of connections (8 by default). Each request borrows one connection
until its reply arrives, so the pool size bounds how many requests run at once; raise `poolSize` when more threads
need to run in parallel.

## Mapping your own types

`Database.collection(name, Class)` maps documents to records or POJOs through Jackson. A property named `id` is stored
as `_id` unless the type declares `_id` itself (`@JsonProperty("_id")`).

```java
public record Order(String id, String status, double total, LocalDateTime placedAt) {
}

Collection<Order> orders = shop.collection("orders", Order.class);
orders.save(new Order("o-1", "active", 120, LocalDateTime.now()));
List<Order> active = orders.query().filter(field("status").eq("active")).toList();
```

Pass your own `JsonMapper` with `ClientSettings.Builder.objectMapper(...)`: the client registers its module on a copy,
so your mapper is never changed. Implement `DocumentCodec<T>` to map without Jackson.

## Custom value types

The server stores four value types as tagged strings; the client maps them to Java types in both directions.

| Server value | Java type | Example |
|---|---|---|
| `#datetime(2024-01-01T10:00)` | `java.time.LocalDateTime` | `field("placedAt").gte(LocalDateTime.of(2024, 1, 1, 0, 0))` |
| `#time(09:30)` | `java.time.LocalTime` | `field("opensAt").lt(LocalTime.NOON)` |
| `#geo(lat,lng)` | `GeoPoint` | `distance("location", GeoPoint.of(40.71, -74.0), GeoDistanceComparator.SMALLER_THAN, 1_000)` |
| `#vector(v0,v1,...)` | `Vector` | `nearest("embedding", Vector.of(0.12, 0.44, 0.91), 10)` |

- `Document.getDateTime`, `getTime`, `getGeo` and `getVector` read them back.
- The server's datetime has no zone. `Instant`, `OffsetDateTime` and `ZonedDateTime` are refused unless
  `ClientSettings.Builder.temporalZone(zone)` is set; they are then converted to that zone's local date-time on write
  and back on read.
- A plain `String` that looks like a custom value (`#name(...)`) is refused, because the server would read it as one.
- `nearest` is an approximate (index-backed) search; add `.exact()` to scan every row and get the true top `k`.
- Annotate a `double[]` property with `@LwnrdbVector` to store it as a vector.

## Queries

`Collection.query()` starts an immutable pipeline; every step returns a new query.

| Step | Method |
|---|---|
| `FILTER` | `filter(Filter)` with `Filters.field(...).eq/ne/gt/gte/lt/lte/in/notIn/contains`, `and/or/nor/xor/nand`, `distance`, `within`, `nearest`, `script` |
| `MAP` | `map(MapOps.set(field, MidOps...), MapOps.remove(field))`, each optionally `.when(filter)` |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `groupBy(field)`, `distinct()`, `distinct(field)`, `join(collection, localField, remoteField, asField)` |
| `SORT`, `SKIP`, `LIMIT` | `sort(field, Order)`, `skip(n)`, `limit(n)` |
| `REDUCE` | `reduce(script)`, `reduce(script, initialValue, resultField)` |
| `COUNT` | `count()` |

Run a query with `toList()` (decoded by the collection's codec), `toDocuments()` (for rows of another shape, such as
`groupBy`), `first()`, `count()` or `analyze()`. `dirtyRead()` reads without the collection lock.

## Transactions

```java
String id = client.transaction(tx -> {
    TransactionCollection<Document> accounts = tx.collection("bank", "accounts");
    accounts.save(debit);
    accounts.save(credit);
    return tx.id();
});
```

The body commits when it returns and rolls back when it throws. A transaction keeps one connection until it ends.
Its writes take the collection's write lock, so a read of that collection from outside the transaction waits for the
commit (use `ReadMode.DIRTY` to look without waiting). The server rolls back a transaction that is idle longer than
`transactionIdleTimeoutMs` (30 s by default); its next request fails with `409-16`.

## Live queries

```java
Subscription subscription = orders.listen(
        orders.query().filter(field("status").eq("pending")),
        update -> render(update.results()));
...
subscription.close();
```

The handler receives the full result immediately (`update.initial()`), then again whenever a write changes it.
Updates arrive on one dispatcher thread per connection, in order. Subscriptions use their own connections, never
pooled ones, and spread over more connections when the server's per-connection limit is reached. `onEnded` is called
when the server ends a listen (for example, its collection was dropped); `onError` when the handler threw or the
connection was lost, which ends the subscription.

## Errors and retries

Every server refusal is a `LwnrdbException` carrying the server's `errorCode` (`rawCode()`, `errorCode()`), mapped
by range: `AuthenticationException` (401), `ForbiddenException` (403), `NotFoundException` (404),
`ConflictException` (409), `ListenEndedException` (410), `UnavailableException` (503) and `UnknownOutcomeException`
(`500-33`, `500-34`).

The client retries, per `RetryPolicy` (5 attempts, 50 ms doubling to 2 s by default):

- a refusal whose code is retryable (`503-2/4/5/9/10/16/17/18/19/20/21`);
- a read (`FIND_BY_ID`, `AGGREGATE`, `LIST_*`, `GET_*`, `SERVER_INFO`) whose connection was lost.

It never retries a write that lost its connection (`ConnectionLostException`: the outcome is unknown), an
`UnknownOutcomeException`, or anything inside a transaction. A request with no reply within `requestTimeout` closes its
connection and raises `RequestTimeoutException`.

## TLS

```java
ClientSettings.builder()
        .host("db.example.com", 8989)
        .tls(TlsSettings.trustStore(Path.of("lwnrdb-ca.p12"), storePassword))
        ...
```

`TlsSettings.systemTrust()` uses the JVM's certificate authorities; `TlsSettings.insecure()` trusts any certificate
and skips the host-name check (development only, logs a warning).

## Spring Data and Spring Boot

`lwnrdb-spring-data` adds Spring Data repositories, a template and a transaction manager on top of the client;
`lwnrdb-spring-boot-starter` configures all of it from `lwnrdb.*` properties. Both need Spring Boot 4 (Spring
Framework 7, Spring Data 2026.0) and Java 17. Import the BOM as above and add the starter:

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

| Property | Default | |
|---|---|---|
| `lwnrdb.hosts` | `localhost:8989` | `host:port` list |
| `lwnrdb.username`, `lwnrdb.password` | none | no credentials when the username is unset |
| `lwnrdb.database` | none | the database of an entity whose `@LwnrdbEntity` names none |
| `lwnrdb.pool-size`, `connect-timeout`, `request-timeout`, `acquire-timeout`, `shutdown-timeout`, `max-reply-bytes`, `temporal-zone` | the client's | the `ClientSettings` of the same name |
| `lwnrdb.tls.enabled`, `tls.insecure`, `tls.trust-store`, `tls.trust-store-password`, `tls.trust-store-type` | off, `PKCS12` | system trust when enabled without a trust store |
| `lwnrdb.retry.max-attempts`, `retry.initial-backoff`, `retry.max-backoff` | the client's | the `RetryPolicy` |

The starter uses the application's `JsonMapper` bean when there is one, and every `LwnrdbClientSettingsCustomizer`
bean may change the settings before the client connects. A client that cannot connect or log in fails the startup with
a `DataAccessResourceFailureException`.

### Entities

```java
@LwnrdbEntity(collection = "orders")
public record Order(String id, String status, double total, LocalDateTime placedAt) {
}
```

- `@LwnrdbEntity` is optional: without it, the collection is the class name with a lower-case first letter, in
  `lwnrdb.database`. The starter scans the application's packages for annotated entities at startup.
- The id is the property annotated with `@Id`, or the property named `id`. It is stored as `_id`, and it must be a
  `String`.
- A `null` id is replaced by a random UUID before the save, so `save` returns the entity with the id it was stored
  under.
- `@Transient` properties are left out of the stored document.
- Documents are converted by the client's Jackson codec, so `@JsonProperty`, the custom value types and
  `@LwnrdbVector` behave as described above. Query property paths use the stored names.

### Repositories

```java
public interface OrderRepository extends LwnrdbRepository<Order, String> {
    List<Order> findByStatusAndTotalGreaterThanOrderByTotalDesc(String status, double total);

    Page<Order> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);
}
```

The starter enables the repositories of the application's packages; elsewhere, use `@EnableLwnrdbRepositories`.
`LwnrdbRepository` adds paging and sorting to the CRUD methods.

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

- `First`/`Top`, a `Limit`, a `Sort` and a `Pageable` parameter become `SORT`, `SKIP` and `LIMIT` steps.
- `count…By`, `exists…By` and `delete…By`/`remove…By` methods count, check and delete.
- A `Page` runs a second, counting query; a `Slice` asks for one extra row instead.
- `IgnoreCase` is accepted because the server already compares strings without case; it is refused on the id, which
  is compared exactly.
- Refused when the repository is created: `Like`, `NotLike`, `StartingWith`, `EndingWith`, `NotContaining`, `Regex`,
  `Exists`, `IsEmpty`, `IsNotEmpty`, `Near` (use the template's `query` and `nearest`), a sort on more than one
  property (the server's `SORT` step takes one field), and a property that is `@Transient`.

### Transactions

The starter registers a `LwnrdbTransactionManager`, so `@Transactional` works on services:

```java
@Transactional
public void replace(Order cancelled, Order placed) {
    orders.deleteById(cancelled.id());
    orders.save(placed);
}
```

- A Spring transaction is one server transaction on one connection, held from the first call to the commit.
  Repository and template calls inside it use that connection.
- The usual server rules apply: writes lock their collections until the commit, and a transaction idle for longer than
  `transactionIdleTimeoutMs` (30 s by default) is rolled back by the server; its next call fails with a
  `TransientDataAccessResourceException`.
- `REQUIRES_NEW` suspends the outer transaction and opens a second connection. `NESTED` is refused, because the server
  has no savepoints.
- Server errors are translated to Spring's `DataAccessException`s: `400-3` is a `DuplicateKeyException`, a lock timeout
  a `PessimisticLockingFailureException`, schema and before-hook refusals `DataIntegrityViolationException`s, lost
  connections `DataAccessResourceFailureException`s.

### The template

`LwnrdbTemplate` (the `LwnrdbOperations` bean) holds the operations the repositories use, plus ways out to the client:

- `query(Order.class)` returns the client's `Query<Order>` for steps derived queries do not have (`groupBy`, `join`,
  `map`, `nearest`, scripts). Inside a transaction it reads through the transaction.
- `collection(Order.class)` returns the plain `Collection<Order>`, and `execute(client -> ...)` runs anything against
  the `LwnrdbClient`, outside any transaction.
- `wirePath(Order.class, "address.city")` translates a property path to the stored field names for those queries.

`deleteAll()` and `delete…By` read the matching documents first and delete them one by one, because the server has no
bulk delete by filter; on a large collection, drop and re-create the collection instead.

### Health

With Spring Boot's actuator on the classpath, `/actuator/health` includes `lwnrdb`: `UP` while the server answers
`SERVER_INFO` as ready, `OUT_OF_SERVICE` while it is starting or shutting down, `DOWN` when it cannot be reached.
`management.health.lwnrdb.enabled=false` turns it off.

## API reference

The published Javadoc covers every public type. Its text lives in [`apidocs/`](apidocs), one folder per module and one
Markdown file per public type, because the sources carry no comments. At package time `build-tools` copies the sources,
injects each Markdown section as a Javadoc block, and the Javadoc and sources jars are built from that copy.

To document a new public member, add a `## signature` section to its type's file:

```markdown
## save(T)

Inserts or replaces one document.

@param value the document
@return the `_id` and whether it was an insert
```

Parameter types are written by simple name, without generics (`saveAll(List)`, `in(Object...)`). `mvn verify` fails
when a public member has no section, or a section names a member that does not exist.

## Building

```bash
mvn -f clients/java/pom.xml verify                     # unit tests, 95% coverage, Spotless, Checkstyle, PMD, CPD, SpotBugs
mvn package -DskipTests                                # at the repository root: the server jar the integration tests run
mvn -f clients/java/pom.xml verify -Pintegration -Dlwnrdb.jar=$PWD/target/lwnrdb.jar
mvn -f clients/java/pom.xml -P javadoc package         # the Javadoc and sources jars
```

The integration tests start their own servers on free ports. The client builds on JDK 17; Checkstyle runs only on
JDK 21 or newer.

## Releasing

Push a `java-client-vX.Y.Z` tag. `.github/workflows/release-java-client.yml` runs the unit and integration tests
against a server built from the same commit, then signs and deploys the parent, the client, `lwnrdb-spring-data`,
`lwnrdb-spring-boot-starter` and the BOM to Maven Central and creates a GitHub release.

It needs these repository secrets:

| Secret | Value |
|---|---|
| `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` | A user token from the Central Portal (central.sonatype.com → Account → Generate User Token) |
| `GPG_PRIVATE_KEY` | An ASCII-armoured private key (`gpg --armor --export-secret-keys <id>`) whose public key is on a keyserver |
| `GPG_PASSPHRASE` | That key's passphrase |

Before the first release, verify the `io.github.christiancg` namespace in the Central Portal (Namespaces → Add, then
create the temporary public repository it names under the GitHub account).
