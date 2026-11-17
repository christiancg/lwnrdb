# SimpleJS

SimpleJS is the JavaScript engine built into LWNRDB, so you can run your own code inside the database: ad-hoc scripts,
stored procedures, triggers, before-write hooks, scheduled procedures and pipeline steps. Like everything else in the
project it has no dependencies. The lexer, parser, interpreter and regex engine are all written here.

This document is about writing scripts: what the language supports, where a script can run, what it receives and
returns, the `db` module and the limits of the sandbox. The request formats live in [protocol.md](protocol.md).

## The language

The engine follows ES2026 and is always strict. There is no sloppy mode, so assigning to an undeclared name is a
`ReferenceError` and `this` in a plain function call is `undefined`.

What you get is the modern language:

- the full expression grammar and control flow, functions, arrows, closures and classes (private members, static
  blocks, accessors, `new.target`)
- destructuring, spread and rest, template and tagged-template literals
- generators, `async`/`await`, async generators and `for await`, iterators and the iterator helpers
- symbols, proxies and `Reflect`, property descriptors, `Object.freeze`
- modules, with static and dynamic `import` and every form of `export`
- `using` and `await using`
- BigInt, typed arrays, `ArrayBuffer` and `DataView`
- regular expressions with every flag (`dgimsuvy`)
- the standard library, `structuredClone`, `queueMicrotask`, timers, `console`, `crypto` and the whole `Temporal` API

`Date`, `Temporal.Now`, `toLocaleString` and `localeCompare` use `scriptTimeZone` and `scriptLocale` (UTC and en-US by
default), not the machine's, so every node answers the same. Numbers turn into text exactly as they are written to a
document.

`crypto` has three functions: `crypto.randomUUID()`, `crypto.getRandomValues(typedArray)` and
`crypto.hash(algorithm, data[, encoding])`. `hash` takes `sha-1`, `sha-256` or `sha-512` over a string or a
`Uint8Array` and answers hex or base64. Unlike the browser's `crypto.subtle.digest` it is synchronous, because a
procedure computing a hash wants the value, not a promise.

### Known gaps and divergences

These are things a conformant engine has and SimpleJS gets wrong or lacks. None of them should matter for ordinary
procedure or trigger code.

- `arr[i] = v` with an index at or past 2^31 creates a named property instead of growing the array. `length`,
  `new Array(n)` and `Object.defineProperty` do reach indexes up to 2^32 - 1.
- `Object.defineProperty` with an object as the value of `length` throws a `RangeError`.
- `super.m()` when the parent class is a built-in throws a `TypeError`.
- A function written inside a template substitution (`${...}`) has no source text and no line number in a stack trace.
- `\p{...}` property escapes come from the JDK's Unicode data and don't match every code point the Unicode tables say
  they should.
- `localeCompare` and `toLocaleString` honour a locale but only `sensitivity` among the options. A locale tag is
  reduced to its language, script and region and matched against the locales the JDK ships, so `'en-x-foo'` formats as
  `en` and an unknown language falls back to `scriptLocale`. An ill-formed tag is a `RangeError`.
- `Temporal` doesn't support fixed offsets beyond ±18:00.

### Left out on purpose

These ES2026 features are not implemented, mostly because they would break the sandbox or make no sense in a short,
single-threaded run.

- `eval`, the `Function` constructor and its async and generator variants. No code is ever generated from a string.
  `Function` exists so `instanceof Function` works, but calling it throws.
- `ShadowRealm`.
- `Intl`, and with it the non-ISO `Temporal` calendars.
- `SharedArrayBuffer` and `Atomics`.
- `WeakRef` and `FinalizationRegistry`. `WeakMap` and `WeakSet` exist but hold their keys strongly.
- Loading modules from files, URLs or npm. A script can only import the built-in modules and stored procedures. Real
  npm packages need a Node runtime (sockets, `Buffer`, streams), which is not what this is. To reach another database,
  put HTTP in front of it and use `fetch`.
- `Symbol.species`, the `with` statement, proper tail calls and leap seconds.
- Proposals that missed ES2026, such as decorators, `Iterator.zip` and `JSON.rawJSON`.

## Where scripts run

| Surface | Runs as | `db` and `fetch` | Budgets | Console |
|---|---|---|---|---|
| `RUN_SCRIPT` | the caller | yes | `script*` | returned |
| `CALL_PROCEDURE` | the caller | yes | `script*` | returned |
| After trigger | the trigger's definer | yes | `script*`, `triggerTimeoutMs` | logged |
| Before-write hook | no one, it can't touch the database | no | `beforeHook*` | discarded |
| Scheduled procedure | the schedule's definer | yes | `script*`, `scheduleTimeoutMs` | logged |
| Pipeline script | the query's caller | no | `aggregationScript*` | discarded |

### Ad-hoc scripts and stored procedures

[`RUN_SCRIPT`](protocol.md#run_script) takes a `databaseName`, the `script` source and an optional `args` object. A
script is scoped to that one database: any request it makes for another database is refused, `admin` included, so not
even an admin's script can read `admin/users`. The scoped name is `db.name`, so the same script runs anywhere.

```js
import db from "db";
import args from "args";
const user = db.findById(db.name, "users", args.userId);
console.log(`found ${user._id}`);
return { name: user.name };
```

A stored procedure is a named script. [`SAVE_PROCEDURE`](protocol.md#save_procedure) parses it right away, so a broken
procedure is refused when you save it (`400-13`, with the line and column) and not on somebody else's first call.
[`CALL_PROCEDURE`](protocol.md#call_procedure) runs it with the caller's authority, so calling a procedure never grants
more than the caller already has.

Running a script needs admin rights, ownership of the database or a `scriptPermissions` grant on it. The grant is
`NONE`, `RUN` or `MANAGE`. `RUN` lets you run scripts and call procedures, and `MANAGE` also lets you install
procedures, triggers and schedules (a trigger also needs `READ_WRITE` on its collection). Admins and owners have
`MANAGE` implicitly. Being allowed to run a script is separate from what the script can do: every operation it makes
is checked again against the caller's own permissions, and collection schemas still apply to its writes.

The errors are `403-2` scripting disabled, `404-4` unknown database, `400-10` source too large, `400-9` the script
threw or doesn't parse, `400-11` instruction or depth budget, `400-12` memory, `400-15` result too large, `400-20` the
result promise never settled, `400-30` an array past `scriptMaxArrayLength`, `408-1` timeout, `408-2` cancelled,
`503-6` too many scripts running and `409-6` a client transaction is open. The console output, metrics and stack come
back on every outcome, failures included.

### Imports between procedures

A script can import any stored procedure of its own database, so a library is just a procedure that exports instead of
returning. Saved as the procedure `pricing`:

```js
export function total(order) {
    return order.qty * order.price;
}
```

And used from any script of the same database:

```js
import db from "db";
import { total } from "procedures/pricing";
const order = db.findById(db.name, "orders", "o1");
return total(order);
```

Only exported bindings can be imported. A procedure written for `CALL_PROCEDURE`, with a top-level `return`, imports
as `{ default: undefined }`.

`SAVE_PROCEDURE` refuses an import it can't resolve (`400-18`), so install a library before the procedures that use
it. At run time a missing or disabled procedure is `Cannot find module`. A module runs at most once per run, a module
that threw keeps throwing the same error, and a cycle throws `Circular import of module '...'`. Imported code shares the
importer's budgets and deadline, and it runs with the importer's authority: importing changes what code you can reach,
never what it is allowed to do.

Reading the code is a separate right. If the caller doesn't have `MANAGE` on the database (and isn't its owner or an
admin), they can run imported code but `toString()` of an imported function shows only the `[native code]` stub.

Besides `procedures/<name>` there are three built-in modules: `args` (the request's `args`), `db` and `script`.
`script` has `importText(source[, moduleId])`, which runs a string as a module. It is off unless
`scriptTextImportEnabled` is on. Procedure imports can be switched off with `scriptProcedureImportEnabled`.

### The `db` module

`db` reads and writes the run's database. It has no DDL, no index management and no user operations; a migration is a
client creating collections and indexes over the wire and calling a script for the data.

| Method | Returns |
|---|---|
| `db.findById(database, collection, id)` | the document, or `null` |
| `db.aggregate(database, collection, pipeline)` | an array of rows, `[]` when nothing matches |
| `db.cursor(database, collection, pipeline, options)` | an iterator over the rows, one batch at a time |
| `db.save(database, collection, document)` | the stored document (insert or overwrite) |
| `db.insert(database, collection, document)` | the stored document; throws if the `_id` exists |
| `db.bulkSave(database, collection, documents)` | `{inserted, updated}`, the ids of each |
| `db.updateWhere(database, collection, condition, fields)` | how many documents it updated |
| `db.delete(database, collection, id)` | nothing; deleting a missing id does nothing |
| `db.deleteWhere(database, collection, condition)` | how many documents it deleted |
| `db.listCollections(database)` | the collection names |
| `db.listDatabases()` | the database names the run can see |
| `db.transaction(fn)` | what `fn` returns |
| `db.name` | the run's database |

Every failure throws a catchable `Error`: a denied request, a schema violation, a document too large, an unknown
collection (`404-11`, for reads too). Only real absence is a value: `findById` answers `null`, `aggregate` answers
`[]`, and `updateWhere` or `deleteWhere` matching nothing answer `0`. A missing or wrongly typed argument is a
`TypeError`, and nothing is coerced: `db.findById(db.name, "c", 5)` throws instead of looking up the id `"5"`.

```js
import db from "db";
import args from "args";
try {
    db.insert(db.name, "accounts", { _id: args.accountId, plan: "free" });
} catch (e) {
    return "taken";
}
return "created";
```

`updateWhere` and `deleteWhere` take a `FILTER` operator as the condition, the same thing an `aggregate` `FILTER` step
takes. `updateWhere` merges the top-level properties of `fields` into every match and keeps the rest of each document.
Both are the wire [`SAVE`](protocol.md#save-insert-or-update) and [`DELETE`](protocol.md#delete) with a `condition`:
all or nothing, refused past `updateMaxMatches` or `deleteMaxMatches`, and before hooks run for every match.
`updateWhere` also refuses an empty `fields` or one that names `_id`.

```js
import db from "db";
const now = Date.now();
const expired = db.updateWhere(db.name, "sessions",
    { fieldOperatorType: "SMALLER_THAN", field: "expiresAt", value: now },
    { state: "expired" });
const purged = db.deleteWhere(db.name, "sessions",
    { fieldOperatorType: "SMALLER_THAN", field: "expiresAt", value: now - 86400000 });
return { expired, purged };
```

`db.cursor` reads a collection bigger than the memory budget. Each batch is an ordinary aggregate with `SKIP` and
`LIMIT` added, so only one batch counts against `scriptMaxMemoryBytes`. `options.batchSize` defaults to
`scriptCursorBatchSize` (500) and can't go past `scriptCursorMaxBatchSize`. If the pipeline doesn't end in a sort, the
cursor sorts by `_id` so pages don't overlap. It is not a snapshot: a write between two batches can show a document
twice or not at all. Nothing stays open on the server, so you can stop iterating at any point.

```js
import db from "db";
let total = 0;
for (const order of db.cursor(db.name, "orders",
        [{ type: "SORT", fieldName: "_id", ascending: true }], { batchSize: 500 })) {
    total += order.amount;
}
return total;
```

`db.transaction(fn)` runs `fn` in a transaction, committing when it returns and rolling back when it throws. A
transaction holds the write lock of every collection it writes to, so `fn` can't suspend: an async function, a
generator or a returned promise is a `TypeError`. A timeout or a cancellation still rolls back. Inside a transaction
`listCollections` throws (`409-6`). A trigger can't open one, because its run is already a transaction; a schedule can.

```js
import db from "db";
import args from "args";
return db.transaction(() => {
    const from = db.findById(db.name, "accounts", args.from);
    const to = db.findById(db.name, "accounts", args.to);
    if (from.balance < args.amount) {
        throw new Error("not enough funds");
    }
    db.save(db.name, "accounts", { ...from, balance: from.balance - args.amount });
    db.save(db.name, "accounts", { ...to, balance: to.balance + args.amount });
    return "done";
});
```

### Triggers

A trigger binds a stored procedure to the writes on a collection. An after trigger runs once the write has committed,
in the background. A before hook runs inside the write and decides whether it happens (next section). Triggers are off
until `triggersEnabled=true`; the operations to install them work either way. The request is
[`SAVE_TRIGGER`](protocol.md#save_trigger).

An after trigger's procedure gets this as `args`:

```js
{ event, database, collection, id, document, trigger, actingUser, definer, firedAt, depth }
```

`event` is `CREATED`, `UPDATED` or `DELETED`, `actingUser` is who wrote and `definer` is whose authority the run has.
`firedAt` stays the same on every retry. In `batch` mode there is one run for a whole `BULK_SAVE`, or one per
collection and event for a transaction, and `documents` replaces `id` and `document`.

```js
import db from "db";
import args from "args";
db.save(db.name, "audit", {
    collection: args.collection, event: args.event, id: args.id, by: args.actingUser, at: args.firedAt
});
```

A trigger runs as its definer, the user who saved it, not as the writer. Otherwise an audit trigger would fail for
exactly the low-privilege writers you most want to audit. The definer is checked every time the trigger fires: they
need script `RUN` on the database and `READ` on the collection. If they were deleted or lost those rights, the trigger
is disabled with a `disabledReason` and stays disabled until someone re-saves it, even if the rights come back. It
never falls back to the writer or to an admin.

An after trigger runs exactly once. The pending run is recorded durably before the write is acknowledged, and the run's own writes and the
removal of that record commit in one transaction, so a crash either replays the whole run or nothing. That is also why
a trigger can't call `db.transaction`. Console output, which isn't a database effect, can repeat on a replay.

- The run reads its documents when it runs, not when it was fired. Two quick writes to one id get two runs, and both see
  the later version. If the document is gone the run is skipped.
- Inside a transaction a trigger fires for the net effect once the transaction commits: several writes to one id fire
  once, and deleting a document the same transaction created fires nothing.
- A failed run is retried with a doubling backoff up to `triggerMaxAttempts`, then kept as a dead letter for
  `triggerDeadLetterRetentionMs` (at most `triggerDeadLetterMaxRuns` per database). An error raised after the run's
  transaction committed, from a timer or a stray promise, is logged but never retried, since retrying would apply the
  writes twice. The same goes for a commit with an unknown outcome (`500-33` or `409-10`): it is dead-lettered instead
  of retried.
- A cancelled run is dropped, not retried.
- An unreadable trigger definition keeps the run pending and retries it, rather than treating it as deleted.
- Writes a trigger makes fire nothing unless `allowCascade` is on, and then only down to `triggerMaxDepth`.
- A collection holds at most `triggerMaxPerCollection` triggers (`400-32`).
- Dropping a collection or database discards its pending and dead runs.

Nobody waits on an after trigger, so its failure never reaches the writer. It is logged at WARN with the console output
and the stack, and counted in `GET_DATABASE_STATS`. Admins see pending and dead runs with
[`LIST_TRIGGER_RUNS`](protocol.md#list_trigger_runs-admin-only) and replay or discard them with
[`RESOLVE_TRIGGER_RUN`](protocol.md#resolve_trigger_run-admin-only).

There is no dry run for an after trigger. Put the logic in a procedure, test it with `CALL_PROCEDURE`, and make the
trigger a thin wrapper that imports and calls it.

### Before-write hooks

A trigger with `timing: "before"` runs on the writing thread, inside the collection's write lock, before the document
is written. Its procedure exports a function that gets `(document, context)`, with `context` being
`{event, database, collection, trigger, actingUser, firedAt, id}`.

```js
export default function (document, context) {
    if (!document.customerId) {
        throw new Error("customerId is required");
    }
    if (context.event === "DELETED") {
        return;
    }
    return { ...document, total: document.qty * document.price };
}
```

Returning nothing, `null` or `true` accepts the write. A plain object replaces the document. Anything else, or a throw,
refuses the write with `400-21`. A hook that times out or runs out of budget refuses the write with its own code
(`408-1`, `400-11`, `400-12`), and a cancelled one with `408-2`. Every failure refuses the write.

- There is no `db` and no `fetch`, because the hook holds a write lock while a client waits. It can still import a
  procedure for shared code.
- A replacement can't change `_id`, can't replace a delete, and is validated against the collection's schema again.
- Several hooks on one collection run in name order, each one getting the previous one's output. The first refusal
  stops the chain.
- One interpreter serves the whole request: a `BULK_SAVE` of 10,000 documents runs the module body once and shares one
  `beforeHookInstructionBudget` and one `beforeHookTimeoutMs`. The first refusal fails the whole bulk save.
- Console output is thrown away, since a hook runs once per document.
  [`TEST_TRIGGER`](protocol.md#test_trigger) runs a hook against a document you give it, writes nothing and returns the
  console output.
- `batch` mode and `allowCascade` are refused, and the definer is recorded but not checked, because a hook can't touch
  the database.

### Who can change a bound procedure

A trigger, before hook or schedule looks its procedure up by name when it fires, so whoever can change that procedure
changes what runs with the definer's authority. While a binding by another user reaches a procedure, directly or
through its imports, only that definer, a database owner or an admin can save, disable or delete it (`403-4` for
everyone else). A procedure that imports by a computed name, or imports the `script` module, could reach any procedure,
so it counts as reaching all of them. Procedures nobody else has bound stay freely editable.

Only the definer, an owner or an admin can replace or delete someone else's trigger or schedule (`403-5`). Saving one
always makes you its definer; no request field can name another user.

### Scheduled procedures

A schedule runs a stored procedure on a five-field `cron` expression or every `intervalMs` milliseconds, with fixed
`args`. The request is [`SAVE_SCHEDULE`](protocol.md#save_schedule). Cron is evaluated in `scriptTimeZone`, and a
daily schedule fires once across a daylight saving change. Like a trigger it runs as its definer, who needs script
`RUN` on the database, or it is disabled.

- A schedule fires at most once per due instant. A run still going when the next one is due is skipped, and runs missed
  while a node was down are skipped too, not caught up. A job that must not miss anything should be idempotent and
  driven by the data, not by the clock.
- In a cluster each schedule is owned by one node. While membership is changing two nodes can briefly both think they
  own it, so a run can happen twice. Another reason to make jobs idempotent.
- A run isn't a transaction. Open your own `db.transaction(...)` if you need one.
- A run is skipped (and recorded as `SKIPPED`) when its definer or database is at its concurrency limit, and when the
  schedule was changed or deleted while the run was queued.
- `intervalMs` can't be below `scheduleMinIntervalMs` (`400-35`), and one user can have at most
  `scheduleMaxTotalPerUser` schedules across every database (`400-33`).

### Pipeline scripts

An `AGGREGATE` can run a script in three places: a `SCRIPT` operator in a `FILTER`, a `SCRIPT` value in a `MAP`, and the
`REDUCE` step. The formats are in [protocol.md](protocol.md#script-operators-simplejs-in-the-pipeline).

```json
{"type":"FILTER","operator":{"script":"export default (doc) => doc.price * doc.qty > 100;"}}
```

The callable is the module's `export default`, or its top-level `return`. An async or generator function is refused,
since the call can't leave the thread holding the read locks.

One interpreter serves the whole query, so `aggregationScriptInstructionBudget` and `aggregationScriptTimeoutMs` bound
the query, not each row. A runaway predicate stops the query instead of getting a fresh budget per document. There is no
`db`, no `fetch` and no console. A `MAP` script's value counts against `maxEntrySize` (`400-28`) and the pipeline's
result budget (`400-36`) like any other field, and a `REDUCE` result against `scriptMaxResultBytes`. A `SCRIPT` filter
never uses an index, so put it after an indexed `FILTER`. Running a pipeline script needs a script grant on the
database (`READ` is not enough), and a `LISTEN` can't contain one (`400-19`).

### Run history

Unless `scriptRunHistoryEnabled` is off, every finished run is written to the reserved `script_runs` collection of the
database it ran in, so you can find out what ran, what it cost and why it failed with an ordinary `AGGREGATE`. A row
holds the run's identity, outcome, attempt number and metrics, and with `scriptRunHistoryIncludeLogs` its console
output. `skipped` is an outcome you only see here: a trigger whose definer is gone, a schedule whose procedure was
deleted.

Anyone with `READ` on the database can read every row, including error messages, stacks and logs of runs by other users
and of triggers running with more authority. So never log or throw a secret from a script: the history publishes it to
every reader of the database.

- The collection is created on the first run. Clients can read and index it but not write to it, and triggers and hooks
  don't fire on it.
- `scriptRunHistoryKinds` picks what is recorded. By default it is procedures, triggers and schedules; ad-hoc
  `RUN_SCRIPT` is opt-in, and `BEFORE_HOOK` records only refusals.
- Rows are written in the background and best effort. At most `scriptRunHistoryMaxQueued` wait to be written; past
  that a run is not recorded and is counted in `historyDropped`.
- Every `scriptRunHistorySweepMs` the owner deletes rows older than `scriptRunHistoryRetentionMs` and the oldest ones past
  `scriptRunHistoryMaxRows`.
- A message or log line shaped like a custom type (`#tag(fix)`) is stored with a leading backslash (`\#tag(fix)`) so it
  reads back as a string.

### Under clustering

A script sees the cluster the way a client does. Each `db` call is routed to the collection's owner, and
`db.transaction` can span owners through the same two-phase commit the wire protocol uses. The owner checks the user's
permissions against its own copy, so a grant made a moment before the run can still answer `403-1` once.

Where the run itself executes is chosen by script load and locality (`scriptRoutingEnabled`, on by default). If the
chosen node never reports back, `RUN_SCRIPT` answers `503-7` and does not run it again, because it may already have run
there. A before hook runs on the collection's owner. More in [clustering.md](clustering.md#scripts).

## What a run returns

The result is the top-level `return` if the script runs one, else `export default`, else an object of the named
exports, else `null`. A promise there is awaited, and one still pending when everything else has finished fails the run
with `400-20`. The result is measured against `scriptMaxResultBytes` (`400-15`).

`Infinity`, `-Infinity` and `NaN` can't go into a result or a document and are a `TypeError`. `JSON.stringify` still
turns them into `null`, and so do a `MAP` script value and a `REDUCE` accumulator. A `FILTER` predicate is only tested,
so the usual JavaScript truthiness applies.

Every run also reports:

- `logs`: the newest `scriptMaxLogLines` lines of console output, each cut at `scriptMaxLogLineChars`, with
  `logsTruncated` when something was dropped.
- `metrics`: instructions, peak memory, `db` operations and duration, each next to its budget. An aborted run still
  reports what it used.
- `stack`: on an error, the frames it came from, so an unattended trigger tells you where it broke. A timeout or
  cancellation has no frames, since it isn't a bug in the script.

```
TypeError: Cannot read properties of null (reading 'total')
    at applyRule (procedures/rules:12:7)
    at onWrite (procedures/audit:31:14)
    at main:4:1
```

A running script can be listed with [`LIST_SCRIPTS`](protocol.md#list_scripts-admin-only) and stopped with
[`CANCEL_SCRIPT`](protocol.md#cancel_script-admin-only). Cancelling is checked at the same points as the other limits,
so a run blocked in a `db` call stops when that call returns. It answers `408-2`.

## Value types

The database's custom types come into a script as real values, so a geo or vector field can be read, changed and
created from a script.

| Global | Stored as | Properties | Methods |
|---|---|---|---|
| `Geo` | `#geo(lat,lng)` | `lat`, `lng`, `geoHash` | `toString`, `toJSON` |
| `Vector` | `#vector(v0,...,vn)` | `length`, `simHash` | `at`, `toArray`, `toString`, `toJSON` |
| `DbDateTime` | `#datetime(...)` | `year` to `second` | `toString`, `toJSON`, `toTemporal` |
| `DbTime` | `#time(...)` | `hour`, `minute`, `second` | `toString`, `toJSON`, `toTemporal` |

Each has a constructor and a `from(value)` that takes an instance, the stored text or an object with the properties
(an array for `Vector`). `toTemporal()` gives a `Temporal.PlainDateTime` or `PlainTime`, so the calendar arithmetic is
available on stored dates.

A value read from a document keeps its original text when it is saved back, so `#geo(45,-122)` stays `#geo(45,-122)`.
A value the script builds (`new Geo(45, -122)`) is written as `#geo(45.0,-122.0)`. A plain string in the stored form,
such as `'#geo(1,2)'`, becomes that type when it is saved, exactly as if it came over the wire, and a malformed one
(`'#geo(bad)'`) fails the write with a `TypeError`.

## Sandbox and resource limits

Every limit comes from the server's configuration, never from the request.

The instruction budget, the timeout, the call depth and cancellation are checked at loop iterations and function calls.
Native work counts too: a built-in that walks a big array, a regular expression that backtracks, a large string search
are all charged against the same budget and deadline. Going past one of them stops the run with `400-11`, `408-1` or
`408-2`, and a script can't catch it: `try`/`catch` doesn't see it and `finally` blocks don't run, so a script can't
dodge its own timeout.

`scriptMaxMemoryBytes` counts what a script allocates in bulk: long strings, arrays, typed arrays, JSON, the documents
it reads from the database, promises and coroutines. Past it the run stops with `400-12`. It is not a cap on the live
heap. Small allocations, one per instruction, are bounded only by the instruction budget, so size the two together.
What neither sees, like many copies of a big string, is caught by `scriptHeapCeilingPercent`: when the server's heap
stays above that percentage after a garbage collection, the script allocating is stopped.

A few limits throw ordinary, catchable errors instead:

- Recursion inside a built-in (`JSON.parse`, `JSON.stringify`, `structuredClone`, `flat`) stops at 256 levels with
  `RangeError: Maximum nesting depth exceeded`.
- A BigInt bigger than 2^24 bits is `RangeError: Maximum BigInt size exceeded`, and parsing more than 65,536 digits is a
  `RangeError` too.
- An array-like longer than `scriptMaxArrayLength` passed to `apply`, `Reflect.construct` or `Vector.from` is refused
  with `400-30`.

How many scripts run at once is limited per user (`maxConcurrentScriptsPerUser`, 4), per database
(`maxConcurrentScriptsPerDatabase`, 8) and per node (`maxConcurrentScripts`, 16). A caller at their own limit is refused
at once, and the node-wide one waits up to `scriptQueueWaitMs`; both answer `503-6`, which is safe to retry because
nothing ran. Triggers and schedules take the user and database slots as their definer: a trigger waits for a slot and is
retried later without losing an attempt, a schedule skips that occurrence. Before hooks, `TEST_TRIGGER` and pipeline
scripts don't take slots, since a write refused for a slot would be a failed write. The worst case for script memory is
`maxConcurrentScripts + triggerThreads + scheduleThreads` times `scriptMaxMemoryBytes`, on top of `maxMemory`.

### `fetch`

Scripts can make HTTP requests with `fetch` from `RUN_SCRIPT`, procedures, triggers and schedules, but not from before
hooks or pipeline scripts, which hold locks. It is on by default with `scriptFetchAllowlist=*`, which means any public
host. You should narrow it, and the server warns about it at every startup.

- The allowlist takes exact hosts, `*.example.com` wildcards or `*`. An empty list denies everything.
- Only public addresses are reached. Loopback, private, link-local (the cloud metadata endpoint), CGNAT and other
  internal ranges are refused, even through a DNS name, unless the exact host is in the list. A wildcard never opens
  them.
- Only `http` and `https`, and redirects are not followed.
- The body is cut at `scriptFetchMaxResponseBytes`, the request at `scriptFetchTimeoutMs`, and a run can have at most
  `scriptFetchMaxConcurrent` requests in flight.
- A refused host rejects with `fetch: host not allowed`. Any other network failure is just `fetch failed`, with the
  cause in the server log, so a script can't use the error text to scan ports.

## Configuration

These are the keys that shape scripting. What each one means and its default are in
[operations.md](operations.md#configuration).

| Group | Keys |
|---|---|
| Switches | `scriptsEnabled`, `triggersEnabled`, `schedulesEnabled` |
| Sandbox | `scriptInstructionBudget`, `scriptTimeoutMs`, `scriptMaxDepth`, `scriptMaxMemoryBytes`, `scriptHeapCeilingPercent`, `scriptMaxSourceBytes`, `scriptMaxResultBytes`, `scriptMaxArrayLength`, `scriptMaxLogLines`, `scriptMaxLogLineChars` |
| Imports | `scriptProcedureImportEnabled`, `scriptTextImportEnabled` |
| Cursors | `scriptCursorBatchSize`, `scriptCursorMaxBatchSize` |
| `fetch` | `scriptFetchEnabled`, `scriptFetchAllowlist`, `scriptFetchTimeoutMs`, `scriptFetchMaxResponseBytes`, `scriptFetchMaxConcurrent` |
| Concurrency | `maxConcurrentScripts`, `maxConcurrentScriptsPerUser`, `maxConcurrentScriptsPerDatabase`, `scriptQueueWaitMs` |
| Procedures | `maxProceduresPerDatabase`, `procedureCacheSize`, `procedureCacheMaxBytes` |
| Locale | `scriptTimeZone`, `scriptLocale` |
| Triggers | `triggerThreads`, `triggerQueueSize`, `triggerQueueMaxBytes`, `triggerMaxPerCollection`, `triggerTimeoutMs`, `triggerMaxDepth`, `triggerRunLogEnabled`, `triggerRunRetentionMs`, `triggerMaxAttempts`, `triggerRetryBackoffMs`, `triggerRetryMaxBackoffMs`, `triggerDeadLetterRetentionMs`, `triggerDeadLetterMaxRuns` |
| Before hooks | `beforeHookInstructionBudget`, `beforeHookTimeoutMs` |
| Schedules | `scheduleThreads`, `scheduleQueueSize`, `scheduleTickMs`, `scheduleRefreshMs`, `scheduleTimeoutMs`, `scheduleMaxTimeoutMs`, `scheduleMaxPerDatabase`, `scheduleMaxTotalPerUser`, `scheduleMinIntervalMs` |
| Pipeline scripts | `aggregationScriptInstructionBudget`, `aggregationScriptTimeoutMs`, `aggregationScriptMaxSourceBytes` |
| History | `scriptRunHistoryEnabled`, `scriptRunHistoryKinds`, `scriptRunHistoryRetentionMs`, `scriptRunHistoryIncludeLogs`, `scriptRunHistoryMaxErrorChars`, `scriptRunHistoryMaxQueued`, `scriptRunHistoryMaxRows`, `scriptRunHistorySweepMs` |
| Cluster | `scriptRoutingEnabled`, `scriptLocalityWeight` |

## Measuring conformance

SimpleJS is tested against the official tc39/test262 suite, filtered to what a database script can reach, and passes
all of it. CI fails on any new failure, and also on a known failure that starts passing, so the baseline in
`config/test262-baseline.txt` stays honest. `config/test262-exclusions.txt` lists what is not measured and why. Tests
that need sloppy mode are skipped.

```bash
python3 test_utils/test262/test262.py --fetch             # download the pinned corpus
python3 test_utils/test262/test262.py --gate baseline     # what CI runs
python3 test_utils/test262/test262.py --update-baseline   # after fixing a gap
```
