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
> - **Phase 4b — implemented:** a **periodic** background anti-entropy sweep
>   (`antiEntropyIntervalMs`) that reconciles every collection on a fixed interval in
>   addition to the membership-triggered pass — catching replicas left behind by a
>   replication timeout — and **tombstone garbage-collection** that deduplicates the
>   tombstone files and drops deletes older than `tombstoneRetentionMs`. Ownership-
>   handoff cache warm-up was intentionally left as lazy (caches warm on first read).
> - **Phase 5a — implemented:** transactions under clustering, single-owner. The edge
>   forwards a transaction's session to the owner of its collection, which runs it
>   locally (holding the write locks, buffering, serving read-your-writes) and at commit
>   replays and replicates the transaction to a majority as **one atomic batch**. Kept
>   as a fast path when only one owner is involved.
> - **Phase 5b — implemented:** cross-owner distributed transactions via **two-phase
>   commit** with a durable recovery log. The edge coordinates; each written owner is a
>   participant holding its buffered slice + locks. Commit runs PREPARE across all
>   participants and, on a unanimous yes, durably records the decision before driving
>   COMMIT; any no vote or unreachable participant aborts them all. A coordinator or
>   participant crash mid-commit is resolved from the durable log (re-drive the commit,
>   or presumed-abort), with prepared participants re-acquiring their locks on restart.
> - **Planned (later phases):** admin/DDL anti-entropy for nodes that were down during a
>   DDL op.
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
ownership hands off, and a node that was down catches up when it rejoins.

Beyond the membership-triggered pass, a **periodic sweep** runs the same
reconciliation on every collection each `antiEntropyIntervalMs` (default 60s), so a
replica left behind by a replication timeout is repaired without waiting for the next
membership change. At the end of each collection's reconciliation the tombstone file
is **garbage-collected** (`FileSystem.compactTombstones`): duplicate tombstones are
collapsed to the highest version per id and any tombstone older than
`tombstoneRetentionMs` (default 24h) is dropped. Retention must comfortably exceed the
longest expected node downtime, otherwise a delete could be collected before a
still-down replica has converged on it and would be resurrected on rejoin. Owner cache
warm-up on handoff is deliberately lazy — a new owner's cache fills on first read.

## Transactions under clustering (Phases 5a–5b)

The node the client connects to (the **edge**) coordinates the transaction. Each write
is routed to its collection's owner: buffered locally when the edge owns it, else
forwarded to that owner in a `FORWARD_TX_REQUEST` (carrying a stable `txSessionId` = the
edge client id, and the distributed-tx id `txId`), which makes that owner a
**participant**. `START_TRANSACTION` and a read of a not-yet-written collection run
locally; a read of a written collection is forwarded to its participant for
read-your-writes.

Each participant runs its forwarded session on its **own single-thread executor** under
a persistent synthetic client. This matters: the transaction holds the collection write
lock from its first write until commit, and a `ReentrantReadWriteLock` write lock is
thread-owned — running the whole session on one thread keeps acquire/release on the
same thread and makes two concurrent sessions genuinely mutually exclusive rather than
falsely sharing a reentrant lock. Because the lock lives on the owner (where writes are
also coordinated), a concurrent non-transactional write routed to the owner is properly
serialized against the transaction.

**Single-owner fast path (5a).** When only one owner is involved, commit skips 2PC: the
sole owner replays its buffered ops, checks quorum (else `503-2`), and replicates them
to a majority as **one atomic batch** (`REPLICATE_TX`, a `txReplication` payload of
per-collection UPSERT/DELETE entries applied inside one multi-collection lock window so
no other writer interleaves). A replication timeout returns `503-3` but the local commit
stands (anti-entropy reconciles the lagging replicas).

**Cross-owner two-phase commit (5b).** When a transaction spans multiple owners the edge
runs 2PC: `PREPARE_TX` to every participant (each votes yes only after durably recording
a PREPARED marker and confirming quorum), then — on a unanimous yes — the coordinator
**durably records the commit decision** and drives `COMMIT_TX` to all; any no vote or
unreachable participant drives `ABORT_TX` to all and returns `409-7 TRANSACTION_ABORTED`.
Each participant's commit reuses the 5a atomic `REPLICATE_TX` batch to its own replicas.

**Durable recovery log.** The PREPARED markers and the coordinator's COMMIT decision are
stored as records in the `admin/transactions` collection (keyed `{dtxId}|part` /
`{dtxId}|coord`, alongside the transaction's buffered slice). Presence of the coordinator
marker is the commit point: present ⇒ commit, absent ⇒ **presumed abort**. On restart
(`Tx2pcRecovery`): a prepared participant re-acquires its write locks and asks the
coordinator (`TX_STATUS`) whether to commit or abort; a coordinator that recorded a
commit re-drives `COMMIT_TX` to its participants. Recovery re-runs on every membership
change and on a periodic sweep (which also GCs old outcome markers and logs long in-doubt
transactions).

**Coordinator-loss mitigation.** When a prepared participant cannot reach its
coordinator, it falls back to **cooperative termination**: it polls the *other
participants* (whose addresses it recorded in its PREPARED marker) via `TX_STATUS` and
adopts any definitive decision one of them already reached (a peer reporting `COMMITTED` ⇒
commit, `ABORTED` ⇒ abort). To make this work after a peer has already applied and
forgotten the transaction, each participant retains a short-lived **outcome marker**
(`{dtxId}|outcome`, GC'd after `tombstoneRetentionMs`) so it can still answer. This
resolves the common "coordinator died mid-fan-out" case from the peers that committed. If
*every* reachable participant is still uncertain, the transaction stays in-doubt (holding
its locks) — the residual 2PC blocking case; an operator resolves it with the
`RESOLVE_TRANSACTION` admin op (force-commit or force-abort, broadcast to all members).
In-doubt transactions are surfaced in `GET_DATABASE_STATS` (`inDoubtTransactions`) and in
periodic warning logs. An unsafe lock-releasing timeout is deliberately *not* used;
consensus-durable decisions (fully non-blocking) remain future work.

**Liveness before prepare.** If the edge connection closes (or the edge node crashes)
before commit, the not-yet-prepared session is rolled back — the edge aborts its
participants, and a membership listener reaps sessions of a departed edge node. A session
that has already voted yes is left for recovery (never reaped), so a coordinator commit
is never lost to a premature abort.

## Wire protocol

The node-to-node channel reuses the client transport: line-delimited JSON
(`EJson`) over TCP on `clusterPort`, optionally wrapped in TLS
(`clusterTlsEnabled`, reusing the PKCS12 keystore — all nodes must share the same
keystore for the TLS cluster channel to establish). Every frame is a
`ClusterMessage` envelope `{correlationId, type, secret, sender, members,
replication, forwardBody, actingUser, antiEntropy, txSessionId, txId, txParticipants,
txStatus, txReplication, errorMessage}`; `correlationId` lets a single pooled connection
multiplex many in-flight requests. Message types are
`JOIN_REQUEST`/`JOIN_RESPONSE` and `GOSSIP`/`GOSSIP_ACK` (membership, carrying
`sender`/`members`), `REPLICATE`/`REPLICATE_ACK` (document writes, carrying a
`replication` payload of `{dbName, collName, op: UPSERT|DELETE, documents, ids}`),
`FORWARD_REQUEST`/`FORWARD_RESPONSE` (routing, carrying the Base64-wrapped request or
response JSON in `forwardBody`), `REPLICATE_ADMIN`/`REPLICATE_ADMIN_ACK` (admin
DDL, carrying the Base64-wrapped op in `forwardBody` plus the `actingUser`),
`REPLICATE_USER`/`REPLICATE_USER_ACK` (user/permission ops, carrying the committed
`admin/users` record in a `replication` payload), `DIGEST`/`DIGEST_ACK` and
`PULL`/`PULL_ACK` (anti-entropy, carrying an `antiEntropy` payload of a collection's
`{id, version, deleted}` digest or of pulled `documents`/`versions`),
`FORWARD_TX_REQUEST` (a forwarded transaction operation, carrying the Base64-wrapped
request in `forwardBody` plus a `txSessionId` and `txId`; the reply reuses
`FORWARD_RESPONSE`), `REPLICATE_TX`/`REPLICATE_TX_ACK` (a committed transaction's atomic
write batch, carrying a `txReplication` payload of per-collection replication entries),
and the 2PC control messages `PREPARE_TX`/`PREPARE_TX_ACK` (carrying the participant set for
cooperative termination), `COMMIT_TX`/`COMMIT_TX_ACK`, `ABORT_TX`/`ABORT_TX_ACK`, and
`TX_STATUS`/`TX_STATUS_ACK` (reporting `COMMITTED`/`ABORTED`/`PREPARED`/`UNKNOWN`), all
keyed by `txSessionId`/`txId`. Inbound messages whose `secret` does not match
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
