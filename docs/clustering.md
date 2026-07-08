# Clustering

Multi-node LWNRDB: a set of **fully-replicated** nodes with a **distributed cache**.
Data lives on every node; only the cache (and write coordination) is partitioned,
so aggregate cache capacity scales with the number of nodes instead of requiring
one large machine.

> **Implementation status**
>
> - **Phase 1 — implemented:** node discovery (seed list), membership + gossip,
>   heartbeat failure detection, per-collection ownership calculation, the
>   node-to-node transport (plaintext or TLS, shared-secret authenticated).
> - **Phase 2 — implemented:** synchronous quorum write replication for document
>   writes (`SAVE`/`BULK_SAVE`/`DELETE`). The owner rejects writes it cannot
>   coordinate, commits locally, and replicates to a majority before acknowledging.
> - **Phase 3 — implemented:** transparent request routing. A client may connect to
>   any node; per-collection reads/writes are forwarded to the owner and the
>   response is relayed back, with reads falling back to the local replica when the
>   owner is unreachable.
> - **Planned (later phases):** admin/DDL replication, a replication log +
>   anti-entropy catch-up, ownership handoff on failure, and distributed
>   transactions.
>
> Everything is gated by `clusterEnabled`. With `clusterEnabled=false` (default)
> the node behaves exactly as a standalone server.

## Topology: per-collection ownership (distributed mastership)

There is **no single master**. Every collection is consistent-hashed to an
**owner node**; ownership is spread across all nodes, one owner per collection.
The owner is intended to be both the collection's **cache home** (reads route
there) and its **write coordinator** (writes route there, are serialized by the
existing per-collection lock, then replicated). A client can connect to **any**
node, which transparently routes to the owner. Because each collection has exactly
one serializing owner at a time, concurrent writes to the same document never
conflict — preserving the engine's linearizable-per-collection guarantees.

## Membership & discovery

- Each node has a stable `nodeId` (configured, or auto-generated and persisted to
  `filePath/cluster/node.id`).
- A joining node contacts one or more `clusterSeeds` (`host:port`) with a
  `JOIN_REQUEST` and receives the current membership.
- Nodes then **gossip** (`GOSSIP` / `GOSSIP_ACK`) their full membership view plus a
  monotonically increasing **heartbeat counter** every `gossipIntervalMs`.
- **Failure detection** is heartbeat-based: a node whose heartbeat has not advanced
  within `suspectTimeoutMs` is marked `SUSPECT`, and within `deadTimeoutMs` is
  marked `DEAD`. Any node that becomes reachable again (its heartbeat advances) is
  restored to `ALIVE`. Merging prefers the higher `incarnation`, then the higher
  heartbeat, so state converges regardless of gossip order.

## Ownership & the hash ring

`HashRing` places `virtualNodesPerNode` virtual points per **ALIVE** node on a
SHA-256 ring; `OwnershipManager.ownerFor(db, coll)` hashes the `db|coll` key to the
next point clockwise. All nodes compute the same ring from the same membership, so
ownership needs no election. Virtual nodes keep reassignment minimal when
membership changes.

## Write path & quorum

A document write (`SAVE`/`BULK_SAVE`/`DELETE`) is coordinated by the owner:

1. **Guard (before commit).** `OperationProcessor` consults `ClusterCoordinator`
   via `ClusterWriteHelper`. If this node lacks a write quorum it returns
   `503-2 NO_QUORUM`; if it is not the owner it returns `421-1 NOT_COLLECTION_OWNER`
   with the owner's address (retryable — Phase 3 will forward instead of reject).
2. **Local commit.** The owner acquires the collection write lock and commits
   through the normal `executeSave`/`executeBulkSave`/`executeDelete` path.
3. **Replicate.** Still holding the lock, the owner re-reads the committed
   document(s) and `Replicator` broadcasts a `REPLICATE` message to all ALIVE
   peers in parallel, waiting for a **majority** to acknowledge within
   `replicationAckTimeoutMs`. The owner counts as one vote, so it needs
   `majority − 1` peer acks, where `majority = ⌊max(clusterExpectedSize,
   knownMembers) / 2⌋ + 1` (`OwnershipManager.majority()`).
4. **Ack / timeout.** On a majority the client gets `OK`. On timeout the client
   gets `503-3 REPLICATION_TIMEOUT`; the **local commit still stands** and lagging
   replicas are reconciled by Phase 4 anti-entropy.

Replicas apply an inbound `REPLICATE` through `ReplicatedApplyHelper`, reusing the
same execute helpers under the collection write lock; they never re-replicate
because replication is triggered only from the owner's write handlers.

The quorum gate is also what makes ownership handoff safe: a node in a **minority
partition** cannot reach a majority, so it cannot commit divergent writes.

## Request routing & distributed cache

A client may connect to any node. After authenticating/authorizing the request,
the edge node's `MessageProcessor` consults `ClusterRouter`: for a per-collection
operation (`SAVE`/`BULK_SAVE`/`DELETE`/`FIND_BY_ID`/`AGGREGATE`) that this node does
**not** own, it forwards the raw request JSON to the owner in a `FORWARD_REQUEST`
and relays the owner's response JSON verbatim to the client. The owner executes the
forwarded request through `OperationProcessor` directly (bypassing the router, so
there is no forward loop) — which means a forwarded write still passes the owner's
Phase 2 ownership/quorum guard and replication.

Routing a **read** to the owner is what makes the cache distributed: only the owner
keeps that collection resident, so total cache capacity is the sum of the nodes'
budgets. If the owner is unreachable and `readFallbackToLocal=true`, the edge node
serves the read from its own (complete) on-disk replica instead of failing; a write
to an unreachable owner returns `503-4 OWNER_UNREACHABLE`.

Forwarded bodies are Base64-wrapped (`cluster/msg/ForwardBody`) because EJson does
not escape string values, so raw JSON cannot be embedded directly in the outer
message.

### Joins across collections

Routing is decided **once**, at the edge, on the request's *primary* collection
(`request.getCollectionName()`) — there is no per-step routing. So an `AGGREGATE`
on collection **A** (owned by node X) that `JOIN`s collection **B** (owned by node
Y) is forwarded whole to X, and X reads B from its **own local replica** while
executing the join. This works because data is fully replicated (every node has a
complete copy of B), and it is inherent to per-collection ownership: no single node
owns both A and B in general, so *some* joined collection is always read from a
non-owner replica regardless of where the request is routed. Two consequences
follow:

- **Cache distribution is partially eroded for joined collections.** X will cache B
  even though Y is B's cache home, so a frequently-joined collection ends up
  resident on more than one node rather than exactly its owner. The "one cache home
  per collection" property holds for the primary collection of each query, not for
  its join targets.
- **The joined side is read at replica consistency.** X is authoritative for A but
  only a replica for B, so a join can combine an authoritative primary with a
  **possibly-slightly-stale** joined collection if a write to B is mid-replication
  and X was not in the acked majority. Reads of the primary collection remain
  owner-authoritative; only the join targets are best-effort-fresh.

(This assumes B exists on X, which depends on admin/DDL replication — planned for a
later phase — propagating `CREATE_COLLECTION` to every node.)

## Failure & partition behaviour

Because data is fully replicated, losing a node never loses data — every survivor
has a complete copy. When an owner dies, the ring is recomputed and its collections
are reassigned to survivors (no data movement; caches warm lazily). During a
partition, only the majority side accepts writes for its owned collections; the
minority side is read-only until the partition heals. (Reassignment, anti-entropy
catch-up for rejoining nodes, and the read-only minority behaviour are part of the
planned phases.)

## Wire protocol

The node-to-node channel reuses the client transport: line-delimited JSON
(`EJson`) over TCP on `clusterPort`, optionally wrapped in TLS
(`clusterTlsEnabled`, reusing the PKCS12 keystore — all nodes must share the same
keystore for the TLS cluster channel to establish). Every frame is a
`ClusterMessage` envelope `{correlationId, type, secret, sender, members,
replication, forwardBody, errorMessage}`; `correlationId` lets a single pooled
connection multiplex many in-flight requests. Message types are
`JOIN_REQUEST`/`JOIN_RESPONSE` and `GOSSIP`/`GOSSIP_ACK` (membership, carrying
`sender`/`members`), `REPLICATE`/`REPLICATE_ACK` (writes, carrying a `replication`
payload of `{dbName, collName, op: UPSERT|DELETE, documents, ids}`), and
`FORWARD_REQUEST`/`FORWARD_RESPONSE` (routing, carrying the Base64-wrapped request
or response JSON in `forwardBody`). Inbound messages whose `secret` does not match
`clusterSecret` are rejected.

## Configuration reference

See the *Clustering* row of the configuration table in the main
[README](../README.md#configuration). Key settings: `clusterEnabled`,
`clusterPort`, `clusterBindAddress`, `clusterAdvertisedAddress`, `clusterSeeds`,
`nodeId`, `clusterExpectedSize`, `gossipIntervalMs`, `suspectTimeoutMs`,
`deadTimeoutMs`, `replicationAckTimeoutMs`, `virtualNodesPerNode`,
`readFallbackToLocal`, `clusterTlsEnabled`, `clusterSecret`.

## Operations runbook

- **First node:** set `clusterEnabled=true`, a unique `clusterAdvertisedAddress`,
  a non-blank `clusterSecret`, and leave `clusterSeeds` empty.
- **Additional nodes:** same settings, with `clusterSeeds` pointing at one or more
  existing nodes. A new node joins automatically on start.
- **Sizing:** set `clusterExpectedSize` to your steady-state node count so the
  write-quorum majority is computed correctly before membership stabilizes.
- **Security:** use a strong shared `clusterSecret`; enable `clusterTlsEnabled`
  with a shared CA-issued keystore for encrypted inter-node traffic.
