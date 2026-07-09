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
> - **Phase 2b — implemented:** structural admin/DDL replication (`CREATE`/`DROP` of
>   databases, collections and indexes, `REINDEX`, `SET_DATABASE_OWNERS`), serialized
>   by an admin coordinator and applied on every node.
> - **Phase 2c — implemented:** user/permission replication (`CREATE_USER`,
>   `DELETE_USER`, `SET_PASSWORD`, `CHANGE_PERMISSIONS`) via record-shipping.
> - **Phase 4a — implemented:** version-based (last-write-wins) anti-entropy. Every
>   document write is stamped with a version and deletes leave versioned tombstones;
>   on a membership change each node reconciles its collections against the live
>   members (digest exchange + pull-newest), converging every id to the highest
>   version seen anywhere. This makes ownership handoff and rejoining-node catch-up
>   data-safe (no committed write is lost or a deleted document resurrected).
> - **Planned (later phases):** 4b — a periodic background anti-entropy sweep and
>   ownership-handoff cache warm-up; admin/DDL anti-entropy for nodes that were down
>   during a DDL op; and distributed transactions.
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

## Admin / DDL replication

Admin and DDL operations mutate cluster-wide metadata (databases, collections,
indexes) rather than a single collection's documents, so they are not per-collection
hash-owned. Instead a single **admin coordinator** — the owner of a reserved ring key
(`OwnershipManager.isAdminCoordinator`), chosen and handed off by the same
consistent-hash + membership machinery — serializes them:

1. A non-coordinator node **forwards** the admin op to the coordinator (reusing the
   `FORWARD_REQUEST` path), carrying the authenticated **acting username**.
2. The coordinator **executes it locally**, then re-executes it on a **majority** of
   peers via a `REPLICATE_ADMIN` broadcast (waiting for `⌈N/2⌉` acks). DDL is
   replicated by **re-execution**, not by shipping rows, because the effect is a
   filesystem/metadata change (create a folder, rebuild an index from the node's own
   documents) that each node must perform locally and deterministically.
3. A node without a write quorum rejects an admin op up front (`503-2 NO_QUORUM`) —
   the same split-brain protection as document writes.

The acting username travels edge → coordinator → peers on the message and is applied
on each node through a short-lived **synthetic client**
(`ClientTracker.registerForwardedClient`), so an op like `CREATE_DATABASE` records
the creator as owner identically on every node rather than losing that identity when
executed away from the originating connection.

**User and permission ops** (`CREATE_USER`, `DELETE_USER`, `SET_PASSWORD`,
`CHANGE_PERMISSIONS`) are coordinated the same way but replicated by **record-shipping**
rather than re-execution: the coordinator ships the committed `admin/users` record
(a `REPLICATE_USER` message carrying the entry's JSON, or the username to delete) and
each peer upserts/deletes it via `AdminOperationHelper`. This is because re-executing
`CREATE_USER`/`SET_PASSWORD` would re-hash the password with a fresh random salt on
each node, diverging the stored hashes; shipping the already-hashed record keeps every
node byte-identical (and password verification still works everywhere).

## Failure & partition behaviour

Because data is fully replicated, losing a node never loses data — every survivor
has a complete copy. When an owner dies, the ring is recomputed and its collections
are reassigned to survivors (no data movement; caches warm lazily). During a
partition, only the majority side accepts writes for its owned collections; the
minority side is read-only until the partition heals. Reassignment is automatic (the
ring is a pure function of the ALIVE membership) and **anti-entropy** (below) makes
the handoff and the rejoining of a previously-down node data-safe.

## Anti-entropy & versioning (last-write-wins)

Every document write is stamped with a **version** — a node-global monotonic
epoch-millis value (`data/WriteVersion`), assigned by the coordinating owner and
persisted as an extra trailing column of the PK index (`id|position|length|page|version`).
The version is
shipped in the `REPLICATE` payload so replicas store the owner's version rather than
assigning their own, and a node advances its clock past any version it receives
(`WriteVersion.observe`) so it never later assigns a lower one. A **delete** records a
versioned **tombstone** (`{coll}-tombstones.idx`, `id|version`) — needed because a
plain delete cannot converge (a lagging replica that still holds the document would
otherwise resurrect it).

On any membership change, `cluster/AntiEntropyService` (a `MembershipListener`)
reconciles each of this node's collections against the live members:

1. It builds a local **digest** (`id → version` for live documents, plus tombstones)
   and requests each peer's digest via a `DIGEST` message.
2. It computes, per id, the **highest version seen anywhere** (a tombstone wins a tie
   with a live document, so a delete beats a concurrent write at the same version).
3. Where a peer holds the winning live version it `PULL`s that document and applies it
   as a versioned upsert; where a tombstone wins it deletes locally and records the
   tombstone. It never overwrites an id it already holds at the winning version.

Because every node runs the same pull-newest reconciliation and the version totally
orders writes per id, the cluster converges to the latest write regardless of which
node became a collection's owner after a failure — so no committed write is lost when
ownership hands off, and a node that was down catches up when it rejoins. Tombstone
garbage-collection, a periodic (not just membership-triggered) sweep, and owner
cache warm-up are Phase 4b.

## Wire protocol

The node-to-node channel reuses the client transport: line-delimited JSON
(`EJson`) over TCP on `clusterPort`, optionally wrapped in TLS
(`clusterTlsEnabled`, reusing the PKCS12 keystore — all nodes must share the same
keystore for the TLS cluster channel to establish). Every frame is a
`ClusterMessage` envelope `{correlationId, type, secret, sender, members,
replication, forwardBody, actingUser, errorMessage}`; `correlationId` lets a single
pooled connection multiplex many in-flight requests. Message types are
`JOIN_REQUEST`/`JOIN_RESPONSE` and `GOSSIP`/`GOSSIP_ACK` (membership, carrying
`sender`/`members`), `REPLICATE`/`REPLICATE_ACK` (document writes, carrying a
`replication` payload of `{dbName, collName, op: UPSERT|DELETE, documents, ids}`),
`FORWARD_REQUEST`/`FORWARD_RESPONSE` (routing, carrying the Base64-wrapped request or
response JSON in `forwardBody`), `REPLICATE_ADMIN`/`REPLICATE_ADMIN_ACK` (admin
DDL, carrying the Base64-wrapped op in `forwardBody` plus the `actingUser`),
`REPLICATE_USER`/`REPLICATE_USER_ACK` (user/permission ops, carrying the committed
`admin/users` record in a `replication` payload), and `DIGEST`/`DIGEST_ACK` and
`PULL`/`PULL_ACK` (anti-entropy, carrying an `antiEntropy` payload of a collection's
`{id, version, deleted}` digest or of pulled `documents`/`versions`). Inbound messages
whose `secret` does not match `clusterSecret` are rejected.

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
