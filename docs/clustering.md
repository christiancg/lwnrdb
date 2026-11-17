# Clustering

Several LWNRDB nodes can run as one cluster. Every node holds all the data, so losing a node never loses data, and a
client can talk to any node. What is split between the nodes is the work: each collection has one owner node that
coordinates its writes and keeps it in cache, so the cache of the whole cluster is the sum of the nodes' caches.

With `clusterEnabled=false`, the default, none of this applies and a node is a plain standalone server.

The cluster is eventually consistent, with last-write-wins per document. It is not linearizable.
[What the cluster guarantees](#what-the-cluster-guarantees) says exactly what that means.

## Setting up a cluster

Each node needs at least this in its `lwnrdb.cfg`:

```
clusterEnabled=true
clusterPort=9990
clusterAdvertisedAddress=10.0.0.11
clusterSeeds=10.0.0.10:9990
clusterSecret=change_this_to_a_shared_secret_of_32_plus_characters
clusterExpectedSize=3
```

- `clusterAdvertisedAddress` is the address other nodes use to reach this one. It defaults to `127.0.0.1`, so set it.
- `clusterSeeds` lists one or more running nodes as `host:port`. Leave it empty on the first node.
- `clusterSecret` is the same on every node and must have at least 32 characters, or the node refuses to start.
- `clusterExpectedSize` is the number of nodes you plan to run. It must be at least 2, and it is the floor for the
  write majority (see [Write path and quorum](#write-path-and-quorum)).
- `nodeId` can stay empty. The node then generates one on its first start and keeps it in `cluster/node.id` under its
  data folder.

For TLS between nodes set `clusterTlsEnabled=true`. It uses the same PKCS12 keystore as client TLS (`tlsKeystorePath`)
and it is mutual: every node presents the keystore's certificate and only accepts peers whose certificate that keystore
trusts. So every node needs the same keystore, or certificates from the same CA. A clustered node without TLS logs a
`SECURITY WARNING` at every start.

Every node must run the same build. There is no negotiation between versions, and nodes on different builds don't form
a cluster.

`scriptsEnabled` and the `script*` sandbox keys must be the same on every node, because a script runs with the settings
of whichever node it was placed on. Keep `schemaMaxPatternLength` and `schemaMaxPatterns` equal too. The rest of the
cluster keys, with their defaults and limits, are in the [configuration table](operations.md#configuration).

## Topology: per-collection ownership

There is no master. Each collection is mapped to an owner with a consistent hash ring built from the nodes that are
alive (`virtualNodesPerNode` points per node, 128 by default). Every node builds the same ring from the same
membership, so there is no election, and when a node joins or leaves only the collections next to it on the ring move.

The owner of a collection:

- coordinates every write to it and replicates it to the other nodes;
- answers its reads, so it is the only node that keeps that collection in cache;
- fires its triggers and runs its before-write hooks.

The same ring also picks the node that fires each schedule, and the admin coordinator, the one node that serializes
schema and user changes (see [Admin and DDL replication](#admin-and-ddl-replication)).

A new owner doesn't copy anything, since every node already has every document. It just warms its cache as the reads
come in.

## Membership and discovery

A new node contacts its seeds, gets the member list from them, and from then on gossips with the others every
`gossipIntervalMs` (1 second). Every gossip carries a heartbeat counter. A node whose heartbeat doesn't move for
`suspectTimeoutMs` (5 seconds) becomes suspect, and after `deadTimeoutMs` (15 seconds) it is dead and its collections
go to the other nodes. As soon as its heartbeat moves again it is alive and gets them back.

Dead nodes are still probed every `deadProbeIntervalMs`, so the two halves of a healed partition find each other again.
A node nobody has heard from for `deadEvictionMs` (24 hours) is dropped from the member list. Until then it still
counts toward the size of the cluster.

Gossip also carries each node's script load and whether it is still catching up on admin metadata, which is what
script placement looks at.

## Write path and quorum

A `SAVE`, `BULK_SAVE` or `DELETE` goes to the collection's owner, which:

1. refuses with `503-2` if it can't see a majority of the cluster, or with `421-1` if it isn't the owner (requests are
   routed to the owner beforehand, so this only happens when ownership has just moved);
2. commits the write locally, the same way a standalone server does, write-ahead log and group commit included;
3. sends it to every live node and waits up to `replicationAckTimeoutMs` (5 seconds) for a majority to acknowledge it;
4. answers `OK` once a majority has it.

The majority is `floor(max(clusterExpectedSize, known nodes) / 2) + 1`, and the owner counts as one. A dead node is
still known until it is evicted, so a three-node cluster with one node down still needs both survivors.

If the majority doesn't answer in time the client gets `503-3`. The write is not undone: it stays on the owner and on
the replicas that got it, and anti-entropy spreads it to the rest. If ownership moved while the write waited, the
answer is `503-8` for the same reason. Both mean the outcome is unknown, not that the write failed. Sending a write
with an `_id` again is safe, but sending a `SAVE` without one again stores the document twice.

While a group of writes waits for its acknowledgements, lookups by id on the owner can already see them. Scans and
other writers wait until replication is done.

A node on the minority side of a partition can't reach a majority, so it can't accept writes, and the two sides can't
diverge.

Replicas apply what the owner sends them but never pass it on, and they never fire triggers for it.

## Request routing and the distributed cache

After authenticating and checking permissions, a node forwards `SAVE`, `BULK_SAVE`, `DELETE`, `FIND_BY_ID` and
`AGGREGATE` on a collection it doesn't own to that collection's owner, and relays the answer unchanged. The owner runs
the request with all its own checks, so a forwarded write still needs the owner's quorum.

Sending reads to the owner is what makes the cache distributed: only the owner keeps a collection in memory.

When the owner can't be reached, a read is served from the local copy if `readFallbackToLocal` is on (the default),
and that copy may be a little behind. A write answers `503-4`. An owner that was reached but didn't answer in time
also gives `503-4`, reads included, because the local copy might be behind. Clients retry `503-4`.

A client that creates a collection and writes to it right away through another node can get there before the
collection does, since admin changes only wait for a majority. That write answers `503-10`, which is retryable. A
clustered node never answers "collection not found" in that case, because it can't tell a missing collection from one
it hasn't heard of yet.

### Joins across collections

Routing only looks at the request's main collection. An `AGGREGATE` on A that joins B goes to A's owner, which reads B
from its own copy. So a collection that is joined a lot ends up cached on several nodes, and the joined side can be a
moment behind B's owner while a write to B is still replicating.

## Transactions

The node the client is connected to (the edge) coordinates the transaction. Each write goes to its collection's owner,
which buffers it and becomes a participant. A participant holds the write locks of its collections until the commit,
so other writers to those collections wait, the same as on a single server.

Reads inside the transaction see its own writes. A read that touches collections written on two different nodes, such
as an `AGGREGATE` with a `JOIN` between them, answers `421-3` instead of mixing a partial view. The transaction can
still be used after that.

When only one owner is involved, the commit is simple: the owner applies the transaction and replicates it as one
batch, which every replica applies at once, so no node ever shows half of it. Like a single write, it answers `503-3`
when replication times out, and the commit stands.

When several owners are involved, the edge runs a two-phase commit:

1. It asks every participant to prepare. A participant votes yes only if it still has quorum, still owns its
   collections, and has durably written its part and a prepared marker.
2. If everyone voted yes, the edge durably writes the decision to commit and then tells every participant to commit.
   Each one replicates its part as one batch.
3. If any participant voted no or couldn't be reached, everyone aborts and the client gets `409-7`.

The written decision is the commit point: if it exists the transaction commits, and if it doesn't the transaction is
aborted. A prepared participant that restarts asks the coordinator what was decided. If the coordinator is gone it asks
the other participants, and every participant keeps the outcome for `tombstoneRetentionMs` so it can still answer
after it has forgotten the transaction. Recovery runs again on every membership change and on a periodic sweep. Every
replayed write is applied only if nothing newer was written to that document in the meantime.

If no reachable node knows the outcome, the transaction stays in doubt, and a participant that never restarted keeps
its locks. Nothing releases them on a timer, because a guess could commit on one node and abort on another.
[`LIST_TRANSACTIONS`](protocol.md#list_transactions-admin-only) shows these transactions across the cluster with each
node's status, and [`RESOLVE_TRANSACTION`](protocol.md#resolve_transaction-admin-only) forces a commit or an abort on
every node once you know which one is right. They also show up in `GET_DATABASE_STATS`, in the
`lwnrdb_in_doubt_transactions` metric and in periodic warnings.

The answers that are specific to a cluster:

- `409-10`: the outcome of the commit is unknown, because the owner stopped waiting or its answer got lost. Send
  `COMMIT` again. The owner remembers how the commit ended and answers with the real outcome.
- `500-33`: the commit was decided but isn't fully applied yet. The transaction stays open, so send `COMMIT` again.
- `409-12`: the connection's previous transaction is still being recovered on a participant.
- `409-13`: a participant lost its part of the transaction, because it restarted or thought the edge was gone. The
  only way out is a rollback.
- `409-14`: a `ROLLBACK` for a transaction that had already committed.

If the client disconnects or the edge crashes before the commit, the participants roll back. A part that was already
prepared is left to recovery, so a decided commit is never lost to an early abort.

A script's `db.transaction` across owners goes through the same two-phase commit.

## Anti-entropy and versioning

Every write gets a version from a hybrid logical clock (wall-clock milliseconds plus a counter), assigned by the owner
and stored with the document on every node. Each node moves its clock past any version it receives, so a later write
always gets a higher version, across restarts too. A replica ignores anything older than what it has, which makes
repeated and reordered messages harmless.

A delete leaves a tombstone with its version. Without it, a node that missed the delete would bring the document back.

Anti-entropy is how nodes that missed something catch up. On every membership change, and every
`antiEntropyIntervalMs` (60 seconds), each node compares its collections with its peers. The comparison starts with one
hash per collection, so collections that already agree cost almost nothing, then narrows down to buckets of ids, and
only the documents that differ are pulled. For each id the highest version anywhere wins, and a tombstone wins a tie
with a live document. Two live copies with the same version are ordered by node id, so every node picks the same one.

This is what repairs a replica after a replication timeout and brings a node that was down up to date. Each collection
also has an incarnation, so if a collection is dropped and created again under the same name, documents from the old
one are never pulled into the new one.

Tombstones are kept for `tombstoneRetentionMs` (24 hours), and only collected in a round where every known node
answered. Set it well above the longest time you expect a node to be down: a node that comes back after its peers
collected a tombstone brings the deleted document back.

## Admin and DDL replication

Databases, collections, indexes, schemas, users, permissions, procedures, triggers and schedules belong to the whole
cluster, not to a collection owner. One node, the admin coordinator, serializes every change to them, and the other
nodes forward those requests to it. The coordinator applies the change, sends the changed records to every node and
waits for a majority, so a node without quorum refuses admin changes with `503-2`, like writes.

Every admin record has a version from the same clock as documents. The newer version wins, a delete leaves a permanent
tombstone, and there is an admin anti-entropy next to the document one, so a node that was down while a database was
created or a user changed catches up when it comes back. A node that finds out late that a database or collection was
dropped doesn't delete it: it renames the folder aside (`<name>.quarantined-...`) and logs it. Check those folders and
delete them yourself.

A coordinator that has just started, or just taken the role over, answers `503-5` until it has merged the admin records
of a majority, so it can't create something that collides with a change it hasn't seen yet. Clients retry it.

`REINDEX` is the exception: every node rebuilds its own indexes from its own documents.

A user or permission created a moment ago can answer `403-1` or `401-2` once on a node that hasn't applied it yet, and
a retry is enough. A password change signs that user out on each node as the change reaches it.

## Scripts

`RUN_SCRIPT` and `CALL_PROCEDURE` can touch any collection of a database, so there is no owner to send them to.
Instead they are placed by load. The node that gets the request samples two nodes (itself included), picks the one with
less script load relative to its `maxConcurrentScripts`, with a preference for the node that owns more of the
database's collections, forwards the run there and relays the answer. Taking the better of two random nodes instead of
the least loaded one keeps every node from sending its scripts to the same place at once. `scriptLocalityWeight` (0 to
100, default 50) sets how much ownership counts, and `0` means load only. Set `scriptRoutingEnabled=false` to run
scripts where they arrive.

A node still catching up on admin metadata is never picked, since it might not have the database or the procedure yet.

A script runs at most once. If the chosen node can't be connected to, the script runs locally instead. Any other
failure, like a timeout or a dropped connection, answers `503-7`, because the script may still be running over there
and running it again could apply its writes twice. Retrying is up to you, since only you know if the script can be
repeated safely. A `503-6` from the chosen node (it is at its script limit) is passed through as it is.

The script runs as the user who sent it, and the node that runs it checks that user's permissions again.

Placement spreads the interpreter's CPU, it doesn't bring the script to its data. Each operation the script runs is
routed to its collection's owner like any other request. Scripts inside a pipeline (`SCRIPT` operators and `REDUCE`)
are different: they run on the collection's owner together with the `AGGREGATE`.

`LIST_SCRIPTS` and `CANCEL_SCRIPT` cover every live node. A run on a node that can't be reached is missing from the
list until that node is back. `GET_DATABASE_STATS` shows each node's placement counters under `scripts`.

## Stored procedures and triggers

Saving or deleting a procedure or a trigger is an admin change, so every node ends up with exactly the same definition,
version and definer included. `CALL_PROCEDURE` is placed like `RUN_SCRIPT`.

A trigger fires only on its collection's owner, never on the replicas, and before-write hooks run there too. The
trigger depth limit holds across nodes.

## Scheduled procedures

Each schedule is placed on the ring like a collection, so one node fires it and another one takes over when that node
dies. A node also needs quorum to fire a schedule, so the minority side of a partition fires nothing.

When a schedule changes owner, the new owner starts from the next future occurrence, so a tick that may have run
already isn't run again. The cost is that a membership change during a tick can drop that tick, and runs missed while a
node was down are skipped, not caught up. Also, right after a node joins or leaves, two nodes can briefly both think
they own a schedule and fire the same occurrence twice. A job that must not run twice has to be idempotent.

## Pending trigger runs are node-local

The records of trigger runs waiting to run (`admin/trigger_runs`) are not replicated. A node that restarts replays its
own, but a node that never comes back takes its pending runs with it: they are lost, never run twice. Replicating them
would add a network round trip to every write that fires a trigger.

## Listenable queries in a cluster

`LISTEN` is served by the node the client is connected to and re-runs against that node's copy. It sees every write,
since every node has all the data, but its copy can be a moment behind the owner. A client that runs an `AGGREGATE`
(answered by the owner) and then a `LISTEN` can see the result go back for a moment. A listener never sees half a
transaction.

## Failure and partition behaviour

When a node dies, the others mark it dead after `deadTimeoutMs` and hand its collections, its schedules and maybe the
admin coordinator role to the survivors. No data moves, every survivor already has it. Until then, writes to its
collections answer `503-4` and reads come from the local copy.

During a partition only the side with a majority accepts writes, for documents and admin changes alike. The minority
side still answers reads from its own copy, which stops getting updates. When the partition heals, anti-entropy brings
both sides back together.

A node that shuts down first stops accepting writes from its peers (forwarded requests answer `503-13`), and leaves the
cluster last, once it has drained. There is no leave message, so the others notice it the same way as a crash, after
`deadTimeoutMs`.

## What the cluster guarantees

What it promises:

- A write answered `OK` is on its owner and on a majority of the nodes, so it survives losing any minority of them.
- Writes to one collection are ordered by its owner, and the minority side of a partition accepts none.
- All nodes end up with the same data. For each document the write with the highest version wins everywhere, and a
  delete wins a tie.
- No node ever shows half a transaction, and a transaction across owners commits everywhere or nowhere.
- A trigger fires once, on the owner. A script runs at most once.
- Schemas, users, permissions and definitions end up the same on every node, and a node that is behind never deletes
  data because of it.

What it doesn't promise:

- It is not linearizable. Ownership comes from each node's own view of the membership, so right after a node joins or
  leaves two nodes can briefly both think they own a collection. Writes accepted on both sides are settled by
  last-write-wins, so one of them is overwritten without anyone being told. Preventing that would need a consensus
  protocol, which LWNRDB doesn't have.
- `503-3`, `503-8` and `503-7` mean the outcome is unknown. The write may well be applied, and then it will spread.
- A transaction whose collections changed owner right before it replicated answers `OK` without having reached a
  majority. It isn't lost, since it has the newest version and anti-entropy spreads it, but the answer promises more
  than happened.
- Some reads can be a little behind: a read served locally because the owner was unreachable, the joined side of a
  `JOIN`, and every `LISTEN`.
- A user or permission change may need one retry on a node that hasn't applied it yet.
- A schedule can fire twice or skip a tick around a membership change, and runs missed while a node was down are
  skipped.
- The pending trigger runs of a node that never comes back are lost.
- A transaction across owners can be left in doubt when its coordinator and every participant are unsure. You resolve
  it by hand.
- A node that was down for longer than `tombstoneRetentionMs` brings deleted documents back when it returns.
- There are no mixed-version clusters, so no rolling upgrades.

## Security

Nodes talk to each other over `clusterPort` with the same line-delimited JSON the clients use. The first thing each
connection sends is the shared secret, and a connection that doesn't, sends the wrong one, or takes longer than
`clusterHandshakeTimeoutMs` is closed without an answer. After that every message still carries the secret, and
messages are capped at `clusterMaxFrameSize`. The client limits (`maxRequestSize`, `maxConnections` and the
authentication and idle timeouts) don't apply to the cluster port.

Everything a peer sends is validated: names, ids and versions. A version more than `clusterMaxClockSkewMs` (1 hour)
ahead of the receiving node's clock is refused, so a node with a badly wrong clock fails loudly instead of pushing
everyone's clock forward. Keep the real skew far below that with NTP.

Forwarded requests carry the name of the user who sent them, and the node that runs them checks that user's
permissions again. But that name is whatever the sender says, so anyone with the cluster secret can act as any user.
The secret and mutual TLS are the trust boundary, so treat the secret like an admin password.

## Operations runbook
### Starting a cluster

Start the first node with `clusterEnabled=true`, its `clusterAdvertisedAddress`, the `clusterSecret`,
`clusterExpectedSize` and no seeds. Start the others the same way, with `clusterSeeds` pointing at a running node. They
join on their own. A standalone node that already holds data can be the first node: its data is kept and copied to the
new ones. Merging two nodes that both hold data is not supported.

### Adding a node

Start it with seeds and an empty data folder. It gets the admin metadata and every document from the others through
anti-entropy, some collections move to it, and it isn't picked for scripts until it has caught up. Update
`clusterExpectedSize` in every node's configuration so the next restart uses the right majority.

### Removing a node

Drain its client traffic at the load balancer, then stop it. The others take over its collections after `deadTimeoutMs`,
and writes to them answer `503-4` until then. The node still counts toward the size of the cluster until it is evicted
after `deadEvictionMs` (24 hours), so for that day the majority doesn't shrink. Lower `clusterExpectedSize` on the
remaining nodes.

### When a node dies

Nothing to do right away: the others take over its collections after `deadTimeoutMs`. Restart it when you can and it
catches up through anti-entropy. If it was down for longer than `tombstoneRetentionMs`, don't bring its old data back,
or deleted documents come back with it: start it with an empty data folder instead, as a replacement.

### Replacing a node

Start the new node with an empty data folder and seeds, like adding one. The dead node still counts toward the size of
the cluster until it is evicted, so for a day a three-node cluster counts four and needs three nodes up to accept
writes.

### Upgrading

Every node must run the same build, so there are no rolling upgrades: stop every node, replace the jar and start them
again. See [Upgrades](operations.md#upgrades) for the rest.

### Backups

`--dump` against any node reads a snapshot of what that node has applied, which can be a moment behind the owners. A
snapshot never splits a transaction.

### A transaction stuck in doubt

Bring the coordinator back if you can. Otherwise decide the outcome from `LIST_TRANSACTIONS` and force it with
`RESOLVE_TRANSACTION`, as described in [Transactions](#transactions).
