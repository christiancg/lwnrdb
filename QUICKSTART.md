# Quickstart

This gets an LWNRDB server running on your machine, stores a document and reads it back, first with the client that
ships inside the server and then from your own code. It takes about five minutes.

LWNRDB is experimental: try it, build things with it, but don't put data in it that you can't afford to lose.

## 1. Start the server

Pick one of the three ways below. Every one of them listens on port `8989`.

The server refuses to start without a password for its admin user, so each option sets one. Use at least 8
characters, and avoid common passwords and the username: the server refuses those too (see
[Bootstrap](README.md#bootstrap)). The admin user is called `admin`.

### With the published Docker image

```bash
docker run -d --name lwnrdb -p 8989:8989 \
  -e LWNRDB_ADMIN_PASSWORD=<choose-a-strong-password> \
  -v lwnrdb-data:/app/db \
  ghcr.io/christiancg/lwnrdb:latest
```

### With Docker, built from source

You need only Git and Docker: the image builds the jar itself.

```bash
git clone https://github.com/christiancg/lwnrdb.git
cd lwnrdb
docker build -t lwnrdb .
docker run -d --name lwnrdb -p 8989:8989 \
  -e LWNRDB_ADMIN_PASSWORD=<choose-a-strong-password> \
  -v lwnrdb-data:/app/db \
  lwnrdb
```

### With the jar

You need Java 27 or newer. Download `lwnrdb-<version>.jar` from the
[latest release](https://github.com/christiancg/lwnrdb/releases/latest), rename it to `lwnrdb.jar`, and put the
password in a `lwnrdb.cfg` next to it:

```bash
echo "defaultAdminPassword=<choose-a-strong-password>" > lwnrdb.cfg
java -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -Djdk.nio.maxCachedBufferSize=262144 -jar lwnrdb.jar
```

To build the jar yourself instead, run `mvn clean package -DskipTests` in a clone (Maven and JDK 27 needed). The jar is
written to `target/lwnrdb.jar`.

The data goes into `./db` and the logs into `./logs`, both relative to the folder you started it from.

### Check that it is up

The server is ready in about a second. With Docker, `docker ps` shows the container as `healthy` once the health check
has run. With the jar, ask it directly. This needs no password and exits `0` once the server answers `READY`:

```bash
java -jar lwnrdb.jar --health
```

## 2. Try it from the command line

The server jar is also a client. When its first argument starts with `--`, it connects to a server instead of starting
one. It reads the password from `LWNRDB_PASSWORD`:

```bash
export LWNRDB_PASSWORD=<the-password-you-chose>
java -jar lwnrdb.jar \
  --exec '{"type":"CREATE_DATABASE","databaseName":"my_db"}' \
  --exec '{"type":"CREATE_COLLECTION","databaseName":"my_db","collectionName":"people"}' \
  --exec '{"type":"SAVE","databaseName":"my_db","collectionName":"people","object":{"_id":"user-1","name":"Alice","age":31}}' \
  --exec '{"type":"AGGREGATE","databaseName":"my_db","collectionName":"people","aggregationSteps":[{"type":"FILTER","operator":{"fieldOperatorType":"GREATER_THAN","field":"age","value":30}}]}'
```

With Docker, the client is already in the container, so replace `java -jar lwnrdb.jar` with
`docker exec -e LWNRDB_PASSWORD=<the-password-you-chose> lwnrdb java -jar app.jar`.

Each `--exec` sends one request, and each reply is printed on its own line. The client stops at the first reply that is
not `OK`. For an interactive prompt use `--shell` instead (see [Client](README.md#client-shell-exec-dumprestore)).

A few things worth knowing before you go further:

- A collection has to exist before you write to it. Saving into a missing one fails with `404-11`.
- `SAVE` inserts or updates: saving a document whose `_id` already exists replaces it.
- An `_id` is a string of 1 to 64 letters, digits, `_` or `-`. Leave it out and the server generates one.
- Every query except a lookup by `_id` is an [aggregation pipeline](README.md#aggregate) of steps such as `FILTER`,
  `SORT`, `LIMIT` and `GROUP_BY`.

## 3. Use it from your code

There are client libraries for Java, TypeScript/JavaScript, Python and .NET. Each one manages a pool of connections and
gives you a query builder, transactions and live queries. The same example in two of them:

**Python** (3.11+, `pip install lwnrdb-client`)

```python
import os

from lwnrdb import ClientSettings, Credentials, LwnrdbClient, Order, field

settings = ClientSettings(credentials=Credentials("admin", os.environ["LWNRDB_PASSWORD"]))

with LwnrdbClient.connect(settings) as client:
    people = client.database("my_db").collection("people")
    people.save({"_id": "user-2", "name": "Bob", "age": 45})
    adults = people.query().filter(field("age") > 30).sort("age", Order.DESC).to_list()
    print(adults)
```

**TypeScript / JavaScript** (Node.js 22.12+, `npm install lwnrdb-client`)

```ts
import { field, LwnrdbClient, Order } from "lwnrdb-client";

await using client = await LwnrdbClient.connect({
    credentials: { username: "admin", password: process.env.LWNRDB_PASSWORD ?? "" },
});
const people = client.database("my_db").collection("people");
await people.save({ _id: "user-2", name: "Bob", age: 45 });
const adults = await people.query().filter(field("age").gt(30)).sort("age", Order.DESC).toArray();
console.log(adults);
```

The full guide for each library, with installation, settings, typed collections, transactions and error handling:

| Language | Package | Guide |
|---|---|---|
| Java 17+ (and Spring Boot) | `io.github.christiancg:lwnrdb-client` | [clients/java](clients/java/README.md) |
| TypeScript / JavaScript | `lwnrdb-client` on npm | [clients/typescript](clients/typescript/README.md) |
| Python 3.11+ | `lwnrdb-client` on PyPI | [clients/python](clients/python/README.md) |
| .NET 10+ | `Lwnrdb.Client` on NuGet | [clients/dotnet](clients/dotnet/README.md) |

No library for your language? Any TCP client works: send one JSON request per line and read one JSON reply per line.
Authenticate first with [`AUTHENTICATE`](README.md#authenticate).

## 4. Next steps

- [Configuration](README.md#configuration): memory, page size, timeouts and limits, all in `lwnrdb.cfg`.
- [Indexes](README.md#create_index): queries scan the collection until the fields they filter on are indexed.
- [Users and permissions](README.md#users--permissions): create a user for your application instead of using `admin`.
- [TLS](README.md#tls--secure-connections): encrypt connections before exposing the server beyond your machine.
- [Backups](README.md#client-shell-exec-dumprestore): `--dump` and `--restore`. A dump reads each database from one
  consistent snapshot, so you can take it while your application keeps writing.
- [Consistency guarantees and known limitations](README.md#consistency-guarantees-and-known-limitations): what survives
  a crash or a power loss, and what does not.
- [Upgrading](docs/compatibility.md): when a new release can take over your data folder as it is, and when it needs a
  dump and restore.
- [Scripting](docs/simplejs.md) and [clustering](docs/clustering.md), once the basics work for you.

## Stopping and cleaning up

```bash
docker stop lwnrdb && docker rm lwnrdb
docker volume rm lwnrdb-data
```

The second line deletes your data. With the jar, stop the server with `Ctrl+C` (it shuts down cleanly) and delete the
`db` and `logs` folders to start over.
