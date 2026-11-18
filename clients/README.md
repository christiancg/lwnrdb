# LWNRDB client libraries

The official clients, one folder per language. Each one supports every operation a client needs, has a fluent query
builder and is published to its language's registry.

| Client | Folder | Registry |
|---|---|---|
| Java | [`java/`](java/README.md) | Maven Central (`io.github.christiancg:lwnrdb-client`) |
| Spring Data and Spring Boot | [`java/`](java/README.md#spring-data-and-spring-boot) | Maven Central (`io.github.christiancg:lwnrdb-spring-data`, `lwnrdb-spring-boot-starter`) |
| TypeScript and JavaScript (Node.js) | [`typescript/`](typescript/README.md) | npm (`lwnrdb-client`) |
| Python (sync and asyncio) | [`python/`](python/README.md) | PyPI (`lwnrdb-client`) |
| .NET (C#, with LINQ) | [`dotnet/`](dotnet/README.md) | NuGet (`Lwnrdb.Client`) |

## The shared contract

[`protocol/`](protocol/README.md) is not a client. It holds the contract every client is tested against: the list of
operations, error codes and custom types, generated from the server, and the exact requests and replies each client
has to produce and understand. The server checks the same files, so a new operation or error code fails the server's
build until it has fixtures, and each client's build until that client supports it.

The protocol itself, and what a new client has to get right, is in the [wire protocol reference](../docs/protocol.md).

## Versioning

Each client is versioned and released on its own, from a `<language>-client-vX.Y.Z` tag. Its release notes name the
`protocolVersion` it speaks and the lowest server release that speaks it, the first `vX.Y.Z` tag whose server has that
`protocolVersion` (see [compatibility](../docs/compatibility.md#client-libraries)). A client release refuses to publish
until such a server release exists, so a server that raises `protocolVersion` is released before its clients.
