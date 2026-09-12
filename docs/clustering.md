# Clustering

Multi-node LWNRDB: a set of **fully-replicated** nodes with a **distributed cache**. Data
lives on every node; only the cache and write coordination are partitioned, so aggregate
cache capacity scales with the number of nodes instead of requiring one large machine.

Everything here is gated by `clusterEnabled`. With `clusterEnabled=false` (the default) a
node behaves exactly as a standalone server, with no behaviour change on any path.

> **Residual future work:** fully non-blocking admin-coordinator handoff, and
> consensus-durable 2PC decisions so a prepared transaction is never left in-doubt when its
> coordinator *and* all peers are uncertain (today an operator resolves that rare case with
> `RESOLVE_TRANSACTION`).

## Topology: per-collection ownership

There is **no single master**. Every collection is consistent-hashed to an **owner node**,
and ownership is spread across all nodes — one owner per collection. The owner is both the
collection's **cache home** (reads route there) and its **write coordinator** (writes route
there, are serialized by the existing per-collection lock, then replicated). A client may
connect to **any** node, which transparently routes to the owner. Because each collection
has exactly one serializing owner at a time, concurrent writes to the same document never
conflict, preserving the engine's linearizable-per-collection guarantees.

## Membership and discovery

- Each node has a stable `nodeId` (configured, or auto-generated and persisted to
  `filePath/cluster/node.id`).
- A joining node contacts one or more `clusterSeeds` (`host:port`) with a `JOIN_REQUEST`
  and receives the current membership.
- Nodes then **gossip** their full membership view plus a monotonically increasing
  **heartbeat counter** every `gossipIntervalMs`.
- **Failure detection** is heartbeat-based: a node whose heartbeat has not advanced within
  `suspectTimeoutMs` is `SUSPECT`, and within `deadTimeoutMs` is `DEAD`. A node whose
  heartbeat advances again is restored to `ALIVE`. Merging prefers the higher
  `incarnation`, then the higher heartbeat, so state converges regardless of gossip order.

Gossip also carries per-node telemetry — script load, script capacity, and two admin
catch-up signals — used for script placement. Adopting a peer's new telemetry deliberately
does **not** count as a membership change: firing the membership listeners every round
would rebuild the ownership ring and re-run anti-entropy for nothing.

## Ownership and the hash ring

`HashRing` places `virtualNodesPerNode` virtual points per **ALIVE** node on a SHA-256
ring; `OwnershipManager.ownerFor(db, coll)` hashes the `db|coll` key to the next point
clockwise. All nodes compute the same ring from the same membership, so ownership needs no
election, and virtual nodes keep reassignment minimal when membership changes. A reserved
ring key elects the **admin coordinator**, and schedules are hashed onto the same ring
under `{db}|.schedules|{name}` — no separate ring or coordinator role for either.

## Write path and quorum

A document write (`SAVE`/`BULK_SAVE`/`DELETE`) is coordinated by the owner:

1. **Guard, before commit.** If this node lacks a write quorum it returns
   `503-2 NO_QUORUM`; if it is not the owner it returns `421-1 NOT_COLLECTION_OWNER` with
   the owner's address. (Request routing normally forwards to the owner rather than
   reaching this rejection — it is the backstop for a direct-to-non-owner write.)
2. **Local commit.** The owner takes the collection write lock and commits through the
   normal execute path.
3. **Replicate.** Still holding the lock, the owner re-reads the committed document(s) and
   broadcasts a `REPLICATE` to all ALIVE peers in parallel, waiting for a **majority** to
   ack within `replicationAckTimeoutMs`. The owner counts as one vote, so it needs
   `majority − 1` peer acks, where
   `majority = ⌊max(clusterExpectedSize, knownMembers) / 2⌋ + 1`.
4. **Ack or timeout.** On a majority the client gets `OK`. On timeout it gets
   `503-3 REPLICATION_TIMEOUT`; the **local commit still stands** and lagging replicas are
   reconciled by anti-entropy.

Replicas apply an inbound `REPLICATE` through `ReplicatedApplyHelper`, reusing the same
execute helpers under the collection write lock. They never re-replicate, because
replication is triggered only from the owner's write handlers — the same seam rule that
keeps a replica from re-firing triggers.

The quorum gate is also what makes ownership handoff safe: a node in a **minority
partition** cannot reach a majority, so it cannot commit divergent writes.

## Request routing and the distributed cache

After authenticating and authorizing a request, the edge node consults `ClusterRouter`:
for a per-collection operation (`SAVE`/`BULK_SAVE`/`DELETE`/`FIND_BY_ID`/`AGGREGATE`) it
does **not** own, it forwards the raw request JSON to the owner in a `FORWARD_REQUEST` and
relays the owner's response verbatim. The owner executes the forwarded request through
`OperationProcessor` directly, bypassing the router — so there is no forward loop, and a
forwarded write still passes the owner's ownership/quorum guard and replication.

Routing a **read** to the owner is what makes the cache distributed: only the owner keeps
that collection resident, so total cache capacity is the sum of the nodes' budgets. If the
owner is unreachable and `readFallbackToLocal=true`, the edge serves the read from its own
complete on-disk replica instead of failing; a write to an unreachable owner returns
`503-4 OWNER_UNREACHABLE`.

Forwarded bodies are Base64-wrapped (`cluster/msg/ForwardBody`) because EJson does not
escape string values, so raw JSON cannot be embedded directly in the outer message.

### Joins across collections

Routing is decided **once**, at the edge, on the request's *primary* collection — there is
no per-step routing. So an `AGGREGATE` on collection **A** (owned by X) that `JOIN`s
collection **B** (owned by Y) is forwarded whole to X, and X reads B from its own local
replica. This works because data is fully replicated, and it is inherent to per-collection
ownership: no single node owns both A and B in general, so *some* joined collection is
always read from a non-owner replica wherever the request goes. Two consequences:

- **Cache distribution is partially eroded for joined collections.** X caches B even though
  Y is B's cache home, so a frequently-joined collection ends up resident on more than one
  node. "One cache home per collection" holds for each query's primary collection, not its
  join targets.
- **The joined side is read at replica consistency.** X is authoritative for A but only a
  replica for B, so a join can combine an authoritative primary with a possibly-slightly-
  stale joined collection if a write to B is mid-replication and X was not in the acked
  majority.

## Scripts

A script has no single owner to route to — it is scoped to a database but may touch any
number of collections in it, owned by different nodes. So `RUN_SCRIPT` and `CALL_PROCEDURE`
are **placed** instead: `ClusterRouter` picks a live node by current script load blended
with how much of that database the node owns, forwards the whole run there, and relays the
response verbatim. This is on by default (`scriptRoutingEnabled=true`) so one node cannot
end up running every script in the cluster just because a load balancer sent it every
connection; `false` restores executing on the node that received the request.

**How the node is chosen.** `cluster/ScriptPlacement.choose(databaseName)` uses **power of
two choices**: sample two distinct eligible members at random and take the better one,
scored as

```
score = (scriptLocalityWeight / 100) * share - loadRatio        higher wins
```

`loadRatio` is load relative to the node's `maxConcurrentScripts` rather than absolute (a
node at 3/4 is nearly full where one at 6/32 is idle). `share` is the fraction of the
scoped database's collections the candidate owns, computed per placement from admin
metadata already in memory and the ring already in memory — no new wire field, no extra
round trip, and deliberately not cached, since a few ring lookups are microseconds against
a run measured in milliseconds. Saturation is checked **before** the score: a sample
already at its cap loses outright, because it could only answer `503-6`. A pair nothing
separates goes to the first of the two samples, which — since the pair is drawn as an
ordered pair — is uniform over the eligible set. A stable order such as `nodeId` would make
two edges sampling the same pair agree, but at the cost that matters more: gossip refreshes
`scriptLoad` once per `gossipIntervalMs` and a short script is long over by then, so on an
idle cluster every pair ties and a stable order would send every run to the same node and
never once to the node that sorts last.

Sampling rather than picking the global best is what avoids herding: every edge sees the
same gossiped view, stale by up to one `gossipIntervalMs`, so a global minimum would send
them all to the same node at once. `scriptLocalityWeight` (0–100, default 50) is a
percentage of the load ratio; **`0` reproduces load-only ordering exactly**, which is what
makes the blend safe on by default. Read the default as *a node owning the whole database
beats an idle rival until it is itself more than half full* — locality cannot herd either,
because as the owner's load ratio climbs past `weight / 100` the score inverts. Since each
node places using its own view, this key is a **local** decision and need not be uniform
across the cluster.

**Eligibility** requires all three: `ALIVE` in the local view, not still catching up on
admin metadata (`adminSyncing`), and an `adminEpoch` at least as high as this node's. The
last two matter because admin/DDL and user ops are replicated to a *majority* and the rest
converge through anti-entropy — without them a script could land on a node that has not
applied the `CREATE_DATABASE`/`SAVE_PROCEDURE` the caller depends on and fail with a
transient `404-4`/`404-8` a local run would never have hit. Two consequences: for up to one
`gossipIntervalMs` after a DDL every peer looks behind, so scripts run locally until the
epoch propagates; and an older node reporting no epoch reads as `0` and is never chosen
once this node's epoch is non-zero — the safe direction, and a reason to roll the whole
cluster promptly. This node itself is always a candidate.

**A script runs at most once.** Only one failure falls back to running here: the target
could not be *connected to*, which proves the request never went on the wire. Every other
failure — a timeout, a reset mid-request, an `ERROR` reply (which the peer also sends when
its handler threw partway through) — leaves the target possibly still executing, and
running the script here as well would apply its writes twice. A duplicated inventory
decrement is a wrong answer no later read can detect, so those answer `503-7` instead and
leave retrying to the caller, who alone knows whether the script is idempotent. The two
outcomes are counted apart in `GET_DATABASE_STATS`: `forwardFallbacks` ran the work exactly
once somewhere, `outcomeUnknown` may have run it on the target and reported nothing. A
`503-6` from the chosen node is neither: it is a real response and is relayed verbatim,
since falling back would route around the very cap protecting the target. A forwarded script is
given the whole script budget, so the forward waits `scriptTimeoutMs +
replicationAckTimeoutMs` rather than the ack timeout sized for a single write. No loop is
possible: the target runs the script through `OperationProcessor` directly.

Authorization stays at the edge, and the acting username travels on the forward so the
target runs as the original user. `scriptsEnabled` and the `script*` sandbox keys must
therefore be **uniform across the cluster** — the sandbox comes from the *executing* node's
configuration, and a target with `scriptsEnabled=false` answers `403-2`, which the edge
relays.

**The trade-off is locality.** Placement spreads interpreter CPU; it does not move the
script to its data. Each operation the script issues is still routed normally (forwarded to
its collection's owner, with `db.transaction` spanning owners through the same 2PC the wire
protocol uses), so a forwarded script pays one round trip per operation against a collection
its host does not own. `scriptLocalityWeight` narrows that — on a database of a few
collections the preferred node usually owns all of them — but it cannot close the gap on a
wide database, where consistent hashing gives every node about the same share.

A **pipeline script** (a `SCRIPT` operator or `REDUCE` step inside an `AGGREGATE`) is the
exception and needs no placement at all: the `AGGREGATE` is forwarded to the collection's
owner as usual, so the script runs beside the data it filters. It has no `db` module and
issues no operations of its own, so there is nothing to round-trip.

**Visibility and cancellation are cluster-wide.** The admin-only `LIST_SCRIPTS` and
`CANCEL_SCRIPT` run on the node that receives them and fan out to every live member — they
are deliberately not routable, or a listing would only ever describe one node. A run is
reported on the node **executing** it, with that node's address, so `LIST_SCRIPTS` on B
shows a run A is executing. `CANCEL_SCRIPT` cancels locally first and broadcasts only when
the run is elsewhere. An unreachable peer is logged and skipped rather than failing the
listing, so a run on a partitioned node is invisible and uncancellable until it is
reachable again. The count `LIST_SCRIPTS` reports and the load placement acts on come from
the same registry, so they cannot disagree.

`GET_DATABASE_STATS` reports placement per node under `scripts`: `routingEnabled`,
`running`, `forwarded`, `forwardFallbacks`, `outcomeUnknown`, `localityWeight`,
`localityPreferred` and `cancelled`.

## Stored procedures and triggers

Procedure and trigger DDL (`SAVE_PROCEDURE`, `DELETE_PROCEDURE`, `SAVE_TRIGGER`,
`DELETE_TRIGGER`) is admin DDL: serialized by the admin coordinator, quorum-guarded,
replicated by **re-execution**, ordered by the admin epoch, and carried on the admin
snapshot so a node that was down for the op catches up on rejoin.

Re-execution is only safe because the coordinator **stamps the derived fields onto the
request** during its own local execution — `version`, `updatedAt`, `updatedBy` and, for a
trigger or schedule, `definer`. Otherwise each peer would compute its own
`System.currentTimeMillis()` and the files would diverge, which admin anti-entropy would
then flip-flop on. The `definer` in particular must be stamped rather than re-derived: a
peer re-executing has no acting user of its own, and two nodes disagreeing about a
trigger's definer would mean the same write runs under different authority depending on
which node owns the collection.

`CALL_PROCEDURE` is placed exactly like `RUN_SCRIPT` (see *Scripts*).

A **trigger fires only on the collection's owner**, because `TriggerHelper` is called from
`OperationProcessor`'s write handlers while a replica applies a `REPLICATE`/`REPLICATE_TX`
through the replicated-apply helpers, which bypass it. The cascade bound holds across nodes
too: `triggerDepth` rides on the request (not a `ThreadLocal`, which would reset on the
node a write is forwarded to), the cluster forward paths preserve it, and the edge zeroes
it for client requests.

A **before-write hook** likewise runs on the owner: the edge forwards raw request JSON, so
an edge-side mutation would be discarded in transit, and the owner is where the write lock
that makes the decision binding is held.

## Scheduled procedures

A schedule is hashed onto the **existing** ring under `{db}|.schedules|{name}`, so
`OwnershipManager.isOwner` answers whether this node should fire it — no new ring key kind,
no new coordinator role. That spreads schedules across the cluster and hands them off
automatically on a membership change, exactly as a collection's ownership does. With
clustering off there is no ring, so the scheduler runs everything locally.

`SAVE_SCHEDULE`/`DELETE_SCHEDULE` are admin DDL on the same terms as trigger DDL above,
and the admin snapshot's conform step also reloads that database's registry so the
scheduler picks a change up without waiting for `scheduleRefreshMs`.

**Handoff skips a tick rather than duplicating one.** A new owner computes the next
*future* occurrence, so an instant the previous owner may already have run is never
replayed. That is the whole at-most-once guarantee, and it is why `nextRunAt` lives only in
memory: persisting a `lastRunAt` would mean an admin write per run and would churn the
admin epoch for no benefit. The cost is the other side of the same coin — a membership
change during a tick can drop that tick, and **missed runs while a node was down are
skipped, not caught up**.

## Ownership-partitioned metadata caches

A trigger only ever fires on its collection's owner, and a schedule only fires on its own,
so a node has no use for the trigger lists or schedules of things it does not own.
`cluster/MetadataCachePruner` — a `MembershipListener` registered immediately after
`OwnershipManager`, since listeners fire in registration order and it must read the rebuilt
ring — drops exactly those on every membership change. The caches are derived from files
every node has, so handoff costs the new owner one lazy re-read and nothing is lost.
Entries are **dropped, never blanked**: the dispatcher looks the list up again when it runs
a queued event, and a cached empty list would make an in-flight trigger silently not fire.

For the same reason `AdminAntiEntropyService` does not read through the cache: building a
snapshot and comparing during a conform load procedures, triggers, schemas and schedules
straight from disk, and a conform write *invalidates* the cache rather than populating it —
otherwise every sweep would pull every procedure source on the node into memory regardless
of what anyone had called.

## Pending trigger runs are node-local

`admin/trigger_runs` is not replicated. A node recovers its own pending runs when it
restarts, the way each participant recovers its own `admin/transactions` markers. A node
that never comes back keeps its pending runs on its own disk where no survivor can see
them: those runs are **lost, not double-applied**. Extending exactly-once across permanent
node loss would mean quorum-replicating each run record before its events are queued — a
network round trip on the write path — and is deliberately not implemented. Best-effort
replication is not an acceptable substitute: a lost completion notification would resurrect
a consumed run and double-apply it, which is the failure the design exists to prevent.

## Admin and DDL replication

Admin and DDL operations mutate cluster-wide metadata rather than one collection's
documents, so they are not per-collection hash-owned. A single **admin coordinator** — the
owner of a reserved ring key, chosen and handed off by the same consistent-hash and
membership machinery — serializes them:

1. A non-coordinator node **forwards** the op to the coordinator, carrying the
   authenticated acting username.
2. The coordinator **executes it locally**, then re-executes it on a **majority** of peers
   via `REPLICATE_ADMIN`. DDL replicates by **re-execution**, not by shipping rows, because
   the effect is a filesystem/metadata change (create a folder, rebuild an index from the
   node's own documents) that each node must perform locally and deterministically.
3. A node without a write quorum rejects an admin op up front (`503-2`) — the same
   split-brain protection document writes get.

The acting username travels edge → coordinator → peers and is applied on each node through
a short-lived synthetic client, so `CREATE_DATABASE` records the creator as owner
identically everywhere rather than losing that identity when executed away from the
originating connection.

**User and permission ops** (`CREATE_USER`, `DELETE_USER`, `SET_PASSWORD`,
`CHANGE_PERMISSIONS`) are coordinated the same way but replicated by **record-shipping**:
the coordinator ships the committed `admin/users` record and each peer upserts or deletes
it. Re-executing `CREATE_USER`/`SET_PASSWORD` would re-hash the password with a fresh
random salt on each node, diverging the stored hashes; shipping the already-hashed record
keeps every node byte-identical.

## Transactions

The node the client connects to (the **edge**) coordinates the transaction. Each write is
routed to its collection's owner: buffered locally when the edge owns it, else forwarded in
a `FORWARD_TX_REQUEST` (carrying a stable `txSessionId` and the distributed-transaction id
`txId`), which makes that owner a **participant**. `START_TRANSACTION` and a read of a
not-yet-written collection run locally; a read of a written collection is forwarded to its
participant for read-your-writes.

Each participant runs its forwarded session on its **own single-thread executor** under a
persistent synthetic client. This matters: the transaction holds the collection write lock
from its first write until commit, and a `ReentrantReadWriteLock` write lock is
thread-owned — running the whole session on one thread keeps acquire and release on the
same thread and makes two concurrent sessions genuinely mutually exclusive rather than
falsely sharing a reentrant lock. Because the lock lives on the owner, a concurrent
non-transactional write routed there is properly serialized against the transaction.

**Single-owner fast path.** When only one owner is involved, commit skips 2PC: the sole
owner replays its buffered ops, checks quorum (else `503-2`), and replicates them to a
majority as **one atomic batch** (`REPLICATE_TX`, applied inside one multi-collection lock
window so no other writer interleaves). A replication timeout returns `503-3` but the local
commit stands.

**Cross-owner two-phase commit.** When a transaction spans multiple owners the edge runs
2PC: `PREPARE_TX` to every participant (each votes yes only after durably recording a
PREPARED marker and confirming quorum), then — on a unanimous yes — the coordinator
**durably records the commit decision** and drives `COMMIT_TX` to all. Any no vote or
unreachable participant drives `ABORT_TX` to all and returns `409-7 TRANSACTION_ABORTED`.
Each participant's commit reuses the same atomic `REPLICATE_TX` batch to its own replicas.

**Durable recovery log.** The PREPARED markers and the coordinator's commit decision are
records in `admin/transactions` (keyed `{dtxId}|part` / `{dtxId}|coord`, alongside the
transaction's buffered slice). Presence of the coordinator marker is the commit point:
present ⇒ commit, absent ⇒ **presumed abort**. On restart a prepared participant
re-acquires its write locks and asks the coordinator via `TX_STATUS` what to do, and a
coordinator that recorded a commit re-drives `COMMIT_TX`. Recovery re-runs on every
membership change and on a periodic sweep, which also GCs old outcome markers and logs
long in-doubt transactions.

**Coordinator-loss mitigation.** A prepared participant that cannot reach its coordinator
falls back to **cooperative termination**: it polls the *other* participants (whose
addresses it recorded in its PREPARED marker) and adopts any definitive decision one of
them reached. So that a peer can still answer after applying and forgetting the
transaction, each participant retains a short-lived **outcome marker**
(`{dtxId}|outcome`, GC'd after `tombstoneRetentionMs`). This resolves the common
"coordinator died mid-fan-out" case. If *every* reachable participant is still uncertain,
the transaction stays in-doubt holding its locks — the residual 2PC blocking case. An
unsafe lock-releasing timeout is deliberately not used.

**The operator surface for that case** is `LIST_TRANSACTIONS`, which fans out to every live
member and aggregates in-doubt transactions by `dtxId` — each row carrying
`{dtxId, coordinator, coordinatorReachable, participants, ageMs, perNodeStatus}` so the
correct outcome can be decided before forcing it with `RESOLVE_TRANSACTION` (force-commit
or force-abort, broadcast to all members). In-doubt transactions also surface per node in
`GET_DATABASE_STATS` and in periodic warning logs.

**Liveness before prepare.** If the edge connection closes or the edge node crashes before
commit, the not-yet-prepared session is rolled back — the edge aborts its participants, and
a membership listener reaps sessions of a departed edge node. A session that has already
voted yes is left for recovery and never reaped, so a coordinator commit is never lost to a
premature abort.

## Failure and partition behaviour

Because data is fully replicated, losing a node never loses data — every survivor has a
complete copy. When an owner dies the ring is recomputed and its collections are reassigned
to survivors (no data movement; caches warm lazily). During a partition only the majority
side accepts writes for its owned collections; the minority side is read-only until the
partition heals. Reassignment is automatic — the ring is a pure function of the ALIVE
membership — and anti-entropy makes both the handoff and the rejoining of a previously-down
node data-safe.

**Shutdown and departure.** `ShutdownCoordinator` leaves the cluster last, after the queues
have drained, so peers keep routing here only while this node can still answer. There is,
however, **no graceful LEAVE message**: a departing node is detected the same way a crashed
one is, by missed heartbeats, so peers wait out `deadTimeoutMs` (15s by default) before
reassigning its collections. During that window writes to those collections fail with
`503-4 OWNER_UNREACHABLE` (reads fall back locally when `readFallbackToLocal` is on).
Adding a LEAVE message so membership reassigns immediately is a worthwhile follow-up; until
then, drain the node's traffic at the load balancer first if that window matters.

## Anti-entropy and versioning

Every document write is stamped with a **version** — a node-global monotonic epoch-millis
value assigned by the coordinating owner and persisted as an extra trailing column of the
PK index (`id|position|length|page|version`). It ships in the `REPLICATE` payload so
replicas store the *owner's* version rather than assigning their own, and a node advances
its clock past any version it receives so it never later assigns a lower one. A **delete**
records a versioned **tombstone** (`{coll}-tombstones.idx`), needed because a plain delete
cannot converge — a lagging replica still holding the document would resurrect it.

On any membership change, `cluster/AntiEntropyService` reconciles each of this node's
collections against the live members:

1. It builds a local **digest** (`id → version` for live documents, plus tombstones) and
   requests each peer's digest.
2. It computes, per id, the **highest version seen anywhere** — a tombstone wins a tie with
   a live document, so a delete beats a concurrent write at the same version.
3. Where a peer holds the winning live version it pulls that document and applies it as a
   versioned upsert; where a tombstone wins it deletes locally and records the tombstone. It
   never overwrites an id it already holds at the winning version.

Because every node runs the same pull-newest reconciliation and the version totally orders
writes per id, the cluster converges to the latest write regardless of which node became a
collection's owner after a failure. No committed write is lost when ownership hands off, and
a node that was down catches up when it rejoins.

Beyond the membership-triggered pass, a **periodic sweep** runs the same reconciliation
every `antiEntropyIntervalMs` (default 60s), so a replica left behind by a replication
timeout is repaired without waiting for a membership change. At the end of each
collection's reconciliation the tombstone file is **garbage-collected**: duplicates collapse
to the highest version per id, and any tombstone older than `tombstoneRetentionMs` (default
24h) is dropped. Retention must comfortably exceed the longest expected node downtime,
otherwise a delete could be collected before a still-down replica has converged on it and
would be resurrected on rejoin. Owner cache warm-up on handoff is deliberately lazy.

### Admin and DDL anti-entropy

Document anti-entropy converges the *contents* of collections but not their *structure*: a
node that was down during a `CREATE`/`DROP DATABASE`/`COLLECTION`/`INDEX`, `REINDEX`,
`SET_DATABASE_OWNERS` or a user op would otherwise never learn of it. Admin records are not
version-stamped, so per-record LWW does not apply. Instead a single cluster-wide **admin
epoch** (`cluster/AdminEpoch`, persisted to `cluster/admin.epoch`) orders the whole admin
state: the coordinator bumps it on each committed admin op and ships it on
`REPLICATE_ADMIN`/`REPLICATE_USER` so live replicas advance, while an absent node falls
behind.

On a membership change and on the same periodic sweep, `cluster/AdminAntiEntropyService`
pulls each live peer's `ADMIN_SNAPSHOT` (`{epoch, databases, collections, users, schemas,
procedures, triggers, schedules}`, built from disk), keeps the **highest-epoch** one, and —
only when it exceeds this node's own epoch — **conforms** local state to it: upsert
snapshot users then delete absent ones; create missing databases and reconcile owners;
create missing collections and reconcile their indexes; then drop collections and databases
absent from the snapshot. Each create/drop takes the target collection's write lock,
mirroring the DDL handlers. A document reconciliation pass follows, so freshly materialized
collections repopulate. Because authority is the highest epoch, a stale rejoining node
never overwrites live state — it catches up instead.

To close the window where a stale node becomes the admin coordinator before it has caught
up, a coordinator rejects coordinated admin ops with a retryable `503-5 ADMIN_SYNCING`
until it has completed one admin reconciliation since starting.

## Wire protocol

The node-to-node channel reuses the client transport: line-delimited EJson over TCP on
`clusterPort`, optionally wrapped in TLS (`clusterTlsEnabled`, reusing the PKCS12 keystore
— all nodes must share the same keystore). Every frame is a `ClusterMessage` envelope
whose `correlationId` lets one pooled connection multiplex many in-flight requests, and
inbound messages whose `secret` does not match `clusterSecret` are rejected.

| Message (+ ack) | Carries | Purpose |
|---|---|---|
| `JOIN_REQUEST` / `JOIN_RESPONSE` | `sender`, `members` | discovery |
| `GOSSIP` / `GOSSIP_ACK` | `sender`, `members` (+ heartbeat and telemetry) | membership, failure detection, script load |
| `REPLICATE` | `replication`: `{dbName, collName, op: UPSERT\|DELETE, documents, ids, versions}` | document write replication |
| `FORWARD_REQUEST` / `FORWARD_RESPONSE` | `forwardBody` (Base64 JSON), `actingUser` | request routing and script placement |
| `REPLICATE_ADMIN` | `forwardBody`, `actingUser`, `adminEpoch` | admin/DDL replication by re-execution |
| `REPLICATE_USER` | `replication` (the committed `admin/users` record), `adminEpoch` | user/permission replication by record-shipping |
| `DIGEST` / `PULL` | `antiEntropy`: a collection's `{id, version, deleted}` digest, or pulled documents | document anti-entropy |
| `ADMIN_SNAPSHOT` | `adminSnapshot`: `{epoch, databases, collections, users, schemas, procedures, triggers, schedules}` | admin/DDL anti-entropy |
| `FORWARD_TX_REQUEST` | `forwardBody`, `txSessionId`, `txId` (reply reuses `FORWARD_RESPONSE`) | a forwarded transaction operation |
| `REPLICATE_TX` | `txReplication`: per-collection entries | a committed transaction's atomic batch |
| `PREPARE_TX` / `COMMIT_TX` / `ABORT_TX` | `txId`, `txParticipants` | 2PC control |
| `TX_STATUS` | `txStatus`: `COMMITTED`/`ABORTED`/`PREPARED`/`UNKNOWN` | recovery and cooperative termination |
| `LIST_TX` | `inDoubtTransactions` | cluster-wide `LIST_TRANSACTIONS` |

## Configuration reference

See the *Clustering* row of the configuration table in the main
[README](../README.md#configuration). Key settings: `clusterEnabled`, `clusterPort`,
`clusterBindAddress`, `clusterAdvertisedAddress`, `clusterSeeds`, `nodeId`,
`clusterExpectedSize`, `gossipIntervalMs`, `suspectTimeoutMs`, `deadTimeoutMs`,
`replicationAckTimeoutMs`, `virtualNodesPerNode`, `readFallbackToLocal`,
`scriptRoutingEnabled`, `scriptLocalityWeight`, `clusterTlsEnabled`, `clusterSecret`,
`antiEntropyIntervalMs`, `tombstoneRetentionMs`.

Two classes of key have cluster-wide constraints: `scriptsEnabled` and the `script*`
sandbox keys must be **uniform**, because the sandbox comes from the executing node; and
`clusterExpectedSize` should match the steady-state node count so the write-quorum majority
is computed correctly before membership stabilizes. `scriptLocalityWeight` is explicitly
per-node.

## Operations runbook

- **First node:** set `clusterEnabled=true`, a unique `clusterAdvertisedAddress`, a
  non-blank `clusterSecret`, and leave `clusterSeeds` empty.
- **Additional nodes:** the same settings, with `clusterSeeds` pointing at one or more
  existing nodes. A new node joins automatically on start.
- **Sizing:** set `clusterExpectedSize` to the steady-state node count.
- **Security:** use a strong shared `clusterSecret`; enable `clusterTlsEnabled` with a
  shared CA-issued keystore for encrypted inter-node traffic.
- **Rolling upgrades:** roll every node before enabling a feature that depends on a new
  gossip field or a new stored field — an older node reports `0` script load and no admin
  epoch (so it attracts placement it should not), and drops unknown fields it re-executes,
  such as a trigger's `timing`.
- **Removing a node:** drain its traffic at the load balancer first — there is no LEAVE
  message, so peers wait out `deadTimeoutMs` before reassigning its collections.
