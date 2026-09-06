# Clustering

Multi-node LWNRDB: a set of **fully-replicated** nodes with a **distributed cache**.
Data lives on every node; only the cache (and write coordination) is partitioned,
so aggregate cache capacity scales with the number of nodes instead of requiring
one large machine.

Clustering is fully implemented. It is entirely gated by `clusterEnabled`: with
`clusterEnabled=false` (the default) a node behaves exactly as a standalone server,
with no behaviour change. The capabilities described below — membership and
discovery, per-collection ownership, quorum write replication, transparent request
routing, admin/DDL and user/permission replication, version-based anti-entropy
(membership-triggered and periodic), and single- and cross-owner transactions —
are all in place.

> **Residual future work:** fully non-blocking admin-coordinator handoff, and
> consensus-durable 2PC decisions so a prepared transaction is never left in-doubt
> when its coordinator and all peers are uncertain (today an operator resolves that
> rare case with `RESOLVE_TRANSACTION`).

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
   with the owner's address (a direct-to-non-owner write; request routing forwards to
   the owner instead of rejecting — see *Request routing* below).
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

(This assumes B exists on X, which relies on admin/DDL replication propagating
`CREATE_COLLECTION` to every node, and on admin/DDL anti-entropy catching up a node that
was down when B was created — both implemented; see *Admin / DDL replication* and
*Admin/DDL anti-entropy* below.)

### Scripts (`RUN_SCRIPT`)

A script is **placed by availability**, not by ownership. There is no owner to route it to — a script is
scoped to a database but may touch any number of collections in it, owned by different nodes — so
`ClusterRouter` instead chooses a live node by current script load and forwards the whole run there,
relaying the target's response JSON verbatim. This is on by default (`scriptRoutingEnabled=true`), so one
node cannot end up running every script in the cluster just because a load balancer sent it every
connection; setting it to `false` restores the older behaviour of executing on the node that received the
request, which is what you want when scripts do many small reads and clients already connect to the node
that owns the data.

`cluster/ScriptPlacement.choose()` makes the choice by **power of two choices**: sample two distinct
`ALIVE` members of the local membership view at random and take the less loaded one — load **relative to
`maxConcurrentScripts`**, not absolute, since a node running 3/4 is nearly full where one running 6/32 is
idle, and a sample already at its cap loses outright to one that is not (a saturated target could only
answer `503-6`). A member reporting capacity `0` is either uncapped or too old to gossip the field, so
that pair falls back to comparing absolute load; ties break on `nodeId`, so two edges sampling the same
pair agree. Picking the globally least-loaded
node would herd — every edge sees the same gossiped view, stale by up to one `gossipIntervalMs`, so they
would all forward to the same node at once and then all see it saturated. Sampling needs no accurate
global view, is O(1), and still keeps the maximum load exponentially closer to the mean than random
placement. `choose()` answering "this node" (or a cluster of one) means the script runs locally.

A **pipeline script** (a `SCRIPT` operator or a `REDUCE` step inside an `AGGREGATE`) is the exception, and
needs no new code: an `AGGREGATE` this node does not own is forwarded to the collection's owner as raw
request JSON, so the script runs on the owner, beside the data it filters. A pipeline script is therefore
the *more* local of the two — it has no `db` module and cannot issue an operation of its own, so there is
nothing for it to round-trip.

The load signal is `NodeInfo.scriptLoad`, the number of script runs executing on a node right now
(`RUN_SCRIPT`, `CALL_PROCEDURE`, trigger dispatch and scheduled runs alike — all interpreter CPU), read by
`ops/ScriptLoad` from the `ops/ScriptRunRegistry` every entry point registers with, and published on each gossip round alongside
`NodeInfo.scriptCapacity` (this node's `maxConcurrentScripts`, from `ops/ScriptAdmission.capacity()`) and
the two admin-catch-up fields below. All four ride the existing gossip payload with no wire change, and adopting a
peer's new values (`NodeInfo.copyTelemetryFrom`) deliberately does **not** count as a membership change:
firing the membership listeners every round would rebuild the ownership ring and re-run anti-entropy for
nothing. A node running an older version reports `0` load and so attracts traffic — roll every node before
setting `scriptRoutingEnabled=true`.

A peer is eligible only if all three hold: it is `ALIVE` in the local membership view, it is **not still
catching up on admin metadata** (`adminSyncing`, gossiped from `AdminAntiEntropyService.hasCompletedAdminSync`),
and it reports an **`adminEpoch` at least as high as this node's**. The last two matter because admin/DDL and
user ops are replicated to a *majority* and the rest converge through admin anti-entropy: without them a
script could land on a node that has not applied the `CREATE_DATABASE`/`SAVE_PROCEDURE` the caller is relying
on and fail with a transient `404-4`/`404-8` that a local run would never have hit. Both signals ride the
same gossip round as the heartbeat and the load. Two consequences worth knowing: for up to one
`gossipIntervalMs` after a DDL every peer looks behind, so scripts run locally until the new epoch
propagates; and an older node that does not report an epoch at all reads as `0`, so once this node's epoch is
non-zero it is never chosen — the safe direction, and another reason to roll the whole cluster promptly. This node itself is always a candidate: running locally is the fallback in every other case too, and
the epoch comparison is against its own epoch.

Quorum is deliberately still not an input: a script that lands on a node lacking quorum fails on its first
write with the existing `503-2`, which is the correct and already-tested outcome. A forward that fails (unreachable target, timeout, `ERROR` reply)
**falls back to local execution** rather than erroring: the script would have run here anyway before
placement existed, so local is always correct and placement can never make a working call fail. The
fallback is logged at WARNING and counted. A `503-6` from the chosen node is **not** one of those
failures: it is a real `FORWARD_RESPONSE`, so it is relayed to the client verbatim rather than retried
locally. Falling back there would let the cluster route around the very cap protecting the target, and the
caller should simply retry. Because a forwarded script must be given the whole script
budget, `forwardScript` waits `scriptTimeoutMs + replicationAckTimeoutMs` rather than the ack timeout
sized for a single write. No forwarding loop is possible: the target runs the script through
`ClusterConnectionHandler.executeForwarded` → `OperationProcessor` directly, bypassing
`MessageProcessor` and therefore `ClusterRouter`.

Authorization stays at the edge (`AuthorizationChecker` runs on the caller's own record before routing),
and the acting username travels on the `FORWARD_REQUEST` so the target runs the script as the original
user. `scriptsEnabled` and the `script*` sandbox keys must therefore be **uniform across the cluster**:
the sandbox comes from the *executing* node's configuration, and a target with `scriptsEnabled=false`
answers `403-2`, which the edge relays.

**The trade-off is locality.** Placement spreads interpreter CPU; it does not move the script closer to
its data. Each operation the script issues is still routed normally by `host/EnforcingDatabaseAccess`
(forwarded to its collection's owner, with `db.transaction` spanning owners through the same 2PC the wire
protocol uses), so a forwarded script is usually remote from the collections it touches and pays one
round trip per operation. A script doing many small reads is fastest on the owner of the collections it
reads; a script that is mostly computation is best spread. `ScriptPlacement` is isolated enough that a
locality term could be added later without touching the routing, wire or execution paths.

`GET_DATABASE_STATS` reports placement per node under `scripts`: `routingEnabled`, `running` (the live
count), `forwarded`, `forwardFallbacks` and `cancelled`.

Because placement decides where a run executes, **visibility and cancellation are cluster-wide from the
start**: the admin-only `LIST_SCRIPTS` and `CANCEL_SCRIPT` operations (`cluster/ScriptRunDirectory`) run on
the node that receives them and fan out to every live member, the pattern `LIST_TRANSACTIONS` already
establishes. Neither is in `ROUTABLE`, `SCRIPT_OPS` or `ADMIN_DDL` — the router must not claim them, or the
listing would only ever describe one node. A run is reported on the node **executing** it, which under
default routing is usually not the node that accepted the request: each row carries that node's address, so
`LIST_SCRIPTS` on node B shows a run node A is executing with A's address. `CANCEL_SCRIPT` cancels locally
first and only broadcasts when the run is not here, so the common case costs no messages; an id no live node
is running answers `cancelled:false` with status `OK`. An unreachable peer is logged and skipped rather than
failing the whole listing, which means a listing is a best-effort view of the live members — a run on a
partitioned node is invisible until it is reachable again, and a `CANCEL_SCRIPT` cannot reach it either. The
run count `LIST_SCRIPTS` reports and the `scriptLoad` placement acts on are the same `ops/ScriptRunRegistry`
by construction, so the two can never disagree.

### Stored procedures and triggers

Procedure and trigger DDL (`SAVE_PROCEDURE`, `DELETE_PROCEDURE`, `SAVE_TRIGGER`, `DELETE_TRIGGER`) is in
`ClusterAdminHelper`'s `ADMIN_DDL`, so it is serialized by the admin coordinator, quorum-guarded, replicated
by **re-execution**, and ordered by the admin epoch — exactly like `SAVE_SCHEMA`/`DELETE_SCHEMA`, which it
now matches in every respect (stored with the data, replicated through the coordinator). They also ride the
`ADMIN_SNAPSHOT` payload, so a node that was down for a DDL op catches up on rejoin.

Re-execution is only safe because the coordinator **stamps the derived fields onto the request** during its
own local execution: `version`, `updatedAt`, `updatedBy` and — for a trigger — `definer`. Without that each
peer would compute its own `System.currentTimeMillis()` and the files would diverge, which the admin
anti-entropy would then flip-flop on. The `definer` in particular must be stamped rather than re-derived: a
peer re-executing has no acting user of its own, and two nodes disagreeing about a trigger's definer would
mean the same write runs under different authority depending on which node owns the collection.

`CALL_PROCEDURE` is placed exactly like `RUN_SCRIPT` (both are in `ClusterRouter`'s `SCRIPT_OPS`): a
procedure is scoped to a database but may touch collections owned by different nodes, so there is no single
owner to route to and it is instead forwarded to a live node chosen by script load when
`scriptRoutingEnabled` is on (the default), else run on the node that received it. Either way each operation it issues is
routed normally, `db.transaction` included.

A **trigger fires only on the collection's owner**, because `TriggerHelper` is called from
`OperationProcessor`'s write handlers and a replica applies a `REPLICATE`/`REPLICATE_TX` through
`ReplicatedApplyHelper`/`ReplicatedTxApplyHelper`, which bypass it. The cascade bound holds across nodes too:
`triggerDepth` rides on the request, and `ClusterConnectionHandler`'s forward paths preserve it (they are
authenticated by `clusterSecret`), while the edge zeroes it for client requests.

### Scheduled procedures

A schedule is hashed onto the **existing** ring under the key `{db}|.schedules|{name}`, so
`OwnershipManager.isOwner` answers whether this node should fire it — no new ring key kind, no new
coordinator role. That spreads schedules across the cluster (a database with ten schedules will usually fire
them from several nodes) and hands them off automatically on a membership change, exactly as a collection's
ownership does. When clustering is off there is no ring, so the scheduler runs everything locally.

`SAVE_SCHEDULE`/`DELETE_SCHEDULE` are in `ADMIN_DDL` alongside the procedure and trigger DDL: coordinator-
serialized, quorum-guarded, replicated by re-execution, ordered by the admin epoch, and carried on the
`ADMIN_SNAPSHOT` payload so a node that was down for the save catches up on rejoin (`conformSchedules`, which
also reloads that database's registry so the scheduler picks the change up without waiting for
`scheduleRefreshMs`). Re-execution is safe for the same reason it is for a trigger: the coordinator stamps
`version`, `updatedAt`, `updatedBy` and `definer` onto the request, and the `definer` in particular must be
stamped rather than re-derived, since two nodes disagreeing about it would mean the job runs under different
authority depending on which node owns the schedule.

**Handoff skips a tick rather than duplicating one.** A new owner computes `nextAfter(now)` — the next
*future* occurrence — so an instant the previous owner may already have run is never replayed. That is the
whole at-most-once guarantee, and it is why `nextRunAt` lives only in memory: persisting a `lastRunAt` would
mean an admin write per run and would churn the admin epoch for no benefit. The cost is the other side of the
same coin: a membership change during a tick can drop that tick, and **missed runs while a node was down are
skipped, not caught up**.

The schedule cache is partitioned by ownership on the same terms as the trigger cache below —
`MetadataCachePruner` drops the schedules this node no longer owns, asking the ring the same question the
scheduler's tick asks — and `AdminAntiEntropyService` reads schedules straight from disk
(`Cache.loadScheduleUncached`) rather than through the cache.

### The trigger cache is partitioned by ownership

A trigger only ever fires on its collection's owner — `ops/TriggerHelper.afterWrite` is called from
`OperationProcessor`'s write handlers, and writes route to the owner — so a node has no use for the
trigger lists of collections it does not own. `cluster/MetadataCachePruner`, a `MembershipListener`
registered immediately after `OwnershipManager` (listeners fire in registration order, so it reads
the rebuilt ring), drops exactly those on every membership change. The cache is derived from
`{db}/{coll}/{coll}-triggers.json`, which every node has, so handoff costs the new owner one lazy
re-read and nothing is lost. Entries are **dropped, never blanked**: `TriggerDispatcher` looks the
list up again when it runs a queued event, and a cached empty list would make an in-flight trigger
silently not fire.

For the same reason `cluster/AdminAntiEntropyService` no longer reads through the cache.
`buildSnapshot` and the `conform*` comparisons load procedures, triggers and schemas straight from
disk (`Cache.loadProcedureUncached`/`loadTriggersUncached`/`loadSchemaUncached`) and a conform write
*invalidates* the cache rather than populating it — otherwise every sweep would pull every procedure
source on the node into memory regardless of what anyone had called.

### Shutdown and departure

`ShutdownCoordinator` leaves the cluster last, after the queues have drained, so peers keep routing
here only while this node can still answer. There is, however, **no graceful LEAVE message**: a
departing node is detected the same way a crashed one is, by missed heartbeats, so peers wait out
`deadTimeoutMs` (15s by default) before reassigning its collections. During that window writes to
those collections fail with `503-4 OWNER_UNREACHABLE` (reads fall back locally when
`readFallbackToLocal` is on). Adding a LEAVE to `ClusterMessageType` so membership reassigns
immediately is a worthwhile follow-up; drain the node's traffic at the load balancer first if that
window matters.

### Pending trigger runs are node-local

`admin/trigger_runs` is not replicated. A node recovers its own pending runs when it restarts, the
way each participant recovers its own `admin/transactions` markers. A node that never comes back
keeps its pending runs on its own disk, where no survivor can see them: those runs are lost, not
double-applied. Extending the exactly-once guarantee across permanent node loss would mean
quorum-replicating each run record before its events are queued — a network round trip on the write
path — and is deliberately not implemented. Best-effort replication is **not** an acceptable
substitute: a lost completion notification would resurrect a consumed run and double-apply it, which
is the failure the design exists to prevent.

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

### Admin/DDL anti-entropy (coordinator-authoritative snapshot)

Document anti-entropy converges the *contents* of collections but not the *structure*: a
node that was down during a `CREATE`/`DROP DATABASE`/`COLLECTION`/`INDEX`, `REINDEX`,
`SET_DATABASE_OWNERS` or a user/permission op would otherwise never learn of that change
on rejoin. Admin records are not version-stamped (they go straight through `FileSystem`
without a `WriteVersion` or tombstone), so per-record LWW does not apply. Instead a single
cluster-wide **admin epoch** (`cluster/AdminEpoch`, persisted to `cluster/admin.epoch`)
orders the whole admin state: the admin coordinator bumps it on each committed admin op,
ships it on the existing `REPLICATE_ADMIN`/`REPLICATE_USER` messages (so live replicas
advance), and a node that was absent falls behind.

On a membership change (and on the same `antiEntropyIntervalMs` periodic sweep)
`cluster/AdminAntiEntropyService` (a `MembershipListener`) pulls each live peer's
authoritative admin snapshot via an `ADMIN_SNAPSHOT` message — `{epoch, databases,
collections, users}` — keeps the **highest-epoch** one, and, only when it exceeds this
node's own epoch, **conforms** local state to it: upsert snapshot users then delete
absent ones; create missing databases (with owners) and reconcile owners on existing
ones; create missing collections and reconcile their indexes (create missing, drop
extras); finally drop local collections and databases absent from the snapshot. Each
create/drop takes the target collection's write lock, mirroring the `OperationProcessor`
DDL handlers. It then triggers a document reconciliation pass so freshly-materialized
collections repopulate. Because authority is decided by the highest epoch, a stale
rejoining node never overwrites live state — it catches up instead.

To close the window where a stale node becomes the admin coordinator before it has caught
up, a coordinator rejects coordinated admin ops with a retryable `503-5 ADMIN_SYNCING`
until it has completed one admin reconciliation since starting. Fully non-blocking
coordinator handoff (catch-up before role assignment) remains future work.

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
To discover which transactions need resolving, the admin-only `LIST_TRANSACTIONS` op fans
out a `LIST_TX` query to every live member and returns the in-doubt (PREPARED) transactions
aggregated by distributed-transaction id — each row carrying `{dtxId, coordinator,
coordinatorReachable, participants, ageMs, perNodeStatus}` so an operator can decide the
correct outcome before forcing it. In-doubt transactions are also surfaced per-node in
`GET_DATABASE_STATS` (`inDoubtTransactions`) and in periodic warning logs. An unsafe
lock-releasing timeout is deliberately *not* used; consensus-durable decisions (fully
non-blocking) remain future work.

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
txStatus, txReplication, inDoubtTransactions, adminSnapshot, adminEpoch, errorMessage}`;
`correlationId` lets a single pooled connection
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
`ADMIN_SNAPSHOT`/`ADMIN_SNAPSHOT_ACK` (admin/DDL anti-entropy, the reply carrying an
`adminSnapshot` payload of `{epoch, databases, collections, users}`; `adminEpoch` also
rides on `REPLICATE_ADMIN`/`REPLICATE_USER` so live replicas advance the admin epoch),
`FORWARD_TX_REQUEST` (a forwarded transaction operation, carrying the Base64-wrapped
request in `forwardBody` plus a `txSessionId` and `txId`; the reply reuses
`FORWARD_RESPONSE`), `REPLICATE_TX`/`REPLICATE_TX_ACK` (a committed transaction's atomic
write batch, carrying a `txReplication` payload of per-collection replication entries),
and the 2PC control messages `PREPARE_TX`/`PREPARE_TX_ACK` (carrying the participant set for
cooperative termination), `COMMIT_TX`/`COMMIT_TX_ACK`, `ABORT_TX`/`ABORT_TX_ACK`, and
`TX_STATUS`/`TX_STATUS_ACK` (reporting `COMMITTED`/`ABORTED`/`PREPARED`/`UNKNOWN`), all
keyed by `txSessionId`/`txId`. `LIST_TX`/`LIST_TX_ACK` reports a node's in-doubt (PREPARED)
transactions in an `inDoubtTransactions` payload for the cluster-wide `LIST_TRANSACTIONS`
aggregation. Inbound messages whose `secret` does not match `clusterSecret` are rejected.

## Configuration reference

See the *Clustering* row of the configuration table in the main
[README](../README.md#configuration). Key settings: `clusterEnabled`,
`clusterPort`, `clusterBindAddress`, `clusterAdvertisedAddress`, `clusterSeeds`,
`nodeId`, `clusterExpectedSize`, `gossipIntervalMs`, `suspectTimeoutMs`,
`deadTimeoutMs`, `replicationAckTimeoutMs`, `virtualNodesPerNode`,
`readFallbackToLocal`, `scriptRoutingEnabled`, `clusterTlsEnabled`, `clusterSecret`,
`antiEntropyIntervalMs`, `tombstoneRetentionMs`.

## Operations runbook

- **First node:** set `clusterEnabled=true`, a unique `clusterAdvertisedAddress`,
  a non-blank `clusterSecret`, and leave `clusterSeeds` empty.
- **Additional nodes:** same settings, with `clusterSeeds` pointing at one or more
  existing nodes. A new node joins automatically on start.
- **Sizing:** set `clusterExpectedSize` to your steady-state node count so the
  write-quorum majority is computed correctly before membership stabilizes.
- **Security:** use a strong shared `clusterSecret`; enable `clusterTlsEnabled`
  with a shared CA-issued keystore for encrypted inter-node traffic.
