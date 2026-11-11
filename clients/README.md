# LWNRDB client libraries

Official clients for the LWNRDB wire protocol, one folder per language. Each one covers every client-facing operation
(the test-only fault operations are left out), offers a fluent query builder and publishes to its language's registry.

| Client | Folder | Registry | Status |
|---|---|---|---|
| Java | [`java/`](java/README.md) | Maven Central (`io.github.christiancg:lwnrdb-client`) | Available |
| Spring Data and Spring Boot | [`java/`](java/README.md#spring-data-and-spring-boot) | Maven Central (`io.github.christiancg:lwnrdb-spring-data`, `lwnrdb-spring-boot-starter`) | Available |
| TypeScript / JavaScript (Node.js) | [`typescript/`](typescript/README.md) | npm (`lwnrdb-client`) | Available |
| Python | `python/` | PyPI | Planned |
| .NET (C#, with LINQ) | [`dotnet/`](dotnet/README.md) | NuGet (`Lwnrdb.Client`) | Available |

## The shared contract

[`protocol/`](protocol/README.md) is not a client: it is the contract every client is tested against.

- `operations.json`, `error-codes.json` and `custom-types.json` are generated from the server's own enums by the
  server's `ProtocolCatalogueTest`. Each client checks its own enums against them.
- `fixtures/requests/` holds the exact request each builder call must produce. The server parses and validates every
  case (`ClientFixturesTest`), and every client maps every case to the call that produces it.
- `fixtures/responses/` holds the replies every client must decode.

A new server operation or error code therefore fails the server's build until it has fixtures, and every client's
build until that client supports it.

## Versioning

Each client is versioned on its own, released from a `<language>-client-vX.Y.Z` tag. A client release states the
lowest server version its fixtures were checked against in its release notes.
