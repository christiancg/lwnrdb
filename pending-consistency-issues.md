# Pending consistency issues

Findings from the intensive consistency audit (2026-06-24), verified against the code but
**not yet fixed**. All are **pre-existing** (not regressions from the recent HIGH/MED/LOW
consistency hardening). Ordered by severity. Line numbers are approximate — re-confirm before
editing.

> Context: the prior hardening already fixed the bulk-save off-by-one, `bulkUpdateFromCollection`
> page corruption, DROP_DATABASE locking, the PkCompaction in-memory position fix, the over-EOF
> guard, the `.idx` torn-line self-heal, the `getWholeCollection`/`streamCollection` PK-index-size
> completeness gate, the admin/databases lock-ordering fix, the grow-update relocation (#7), and the
> object/array hash-candidate re-test in FILTER + index-only COUNT disqualification. The issues below
> are what remains.

## 🟠 MED — DROP_DATABASE leaks (and can resurrect) admin page metadata

**Where:** `src/main/java/org/techhouse/ops/AdminOperationHelper.java` — `deleteDatabaseEntry`
(~`:231-256`) loops `deleteCollectionEntry(dbName, collection)` but **never calls
`deletePageCollections`**. Contrast DROP_COLLECTION: `EventProcessorHelper.processCollectionEvent`
(~`:62-65`) calls both `deleteCollectionEntry` **and** `deletePageCollections`.

**Root cause:** `fs.deleteDatabase` removes only the *user* db folder; `cache.evictDatabase` touches only
the user cache. So the `admin/pages_<db>_<coll>` files plus the in-memory `pages[db|coll]`,
`pages[admin|pages_<db>_<coll>]`, and `pagesPkIndexes[admin|pages_<db>_<coll>]` entries (`AdminCache`)
leak permanently.

**Observable:** dropping a database then recreating a db+collection with the same names leaves stale
`AdminPageEntry` occupancy in cache; `selectPageForInsert` (`AdminCache.java:~280-294`) reads the stale
sizes and mis-places inserts / overfills a page past `maxPageSize`.

**Recommended fix:** have `deleteDatabaseEntry` call `deletePageCollections(dbName, collection)` for each
collection (mirroring the DROP_COLLECTION path), and ensure the `admin/pages_*` files are removed.

---

## 🟠 MED — Page metadata (`pageSize` / `entryCount`) double-counted on every insert

**Where:**
- `src/main/java/org/techhouse/ops/OperationProcessor.java` — `processSaveOperation` insert branch calls
  `cache.updatePageSizeInMemory(...)` **synchronously** (~`:298`), then submits `EntityEvent(CREATED)`
  (~`:310`). Same shape in `processBulkSaveOperation` (~`:236` + `BulkEntityEvent`) and in
  `relocateOnGrowUpdate` (~`:350` + `EntityEvent(CREATED)`).
- `src/main/java/org/techhouse/cache/AdminCache.java` — `updatePageSizeInMemory` (~`:204-217`) does
  `pageSize += bytes` **and** `entryCount += 1`.
- `src/main/java/org/techhouse/ops/AdminOperationHelper.java` — the background CREATED event reaches
  `baseUpdateEntryCount` (~`:88-110`), which finds the *same* in-memory `AdminPageEntry` (already bumped
  synchronously) and adds `+bytes`/`+1` **again**, then persists the doubled value.

**Verified:** update/delete paths are single-counted (no synchronous `updatePageSizeInMemory`); admin
collections are single-counted. The doubled value **persists across restart**:
`AdminCache.loadAdminPagesForCollection` (~`:107-122`) loads the user collection's occupancy straight
from the persisted (doubled) `pages_<db>_<coll>` file; only the `pages_*` admin collections are rebuilt
from their PK index (`rebuildInMemoryPagesFromPkIndex`, ~`:124-136`) — the user collection is not.

**Observable:** pages appear full at ~½ `maxPageSize` → premature page allocation / fragmentation, and
`wouldOverflowPage` (#7 relocation) triggers earlier than necessary. **Not** data corruption and **not**
wrong query results (whole-collection reads use the synchronous PK-index size after the #4 fix). It is an
in-memory/persisted metadata inaccuracy that drives page-placement decisions.

**Recommended fix (needs a small design choice):** make the synchronous `updatePageSizeInMemory` the
single in-memory source of truth and have the background CREATED event only *persist* the current value
(not re-apply the delta in memory); or drop the synchronous call and accept a within-pending-window
first-fit lag. Consider also rebuilding the user collection's `pages[db|coll]` from its PK index at
startup (as the `pages_*` collections already are) so a crash between commit and the background flush
can't leave the persisted occupancy stale-low.

**Test gap:** `EventProcessorHelperTest.processCreateEntityEventTest` and
`AdminOperationHelperTest.test_bulk_update_entry_count_created_inserts_admin_page_entry` exercise only the
background half, so the combined double count is uncaught. Add a test that drives a full
`processSaveOperation` insert and asserts the page's `entryCount`/`pageSize`.

---

## 🟡 MED (opt-in / dirty reads only) — `getEntriesByIds` leaks live mutable PkIndexEntry references

**Where:** `src/main/java/org/techhouse/cache/UserCache.java` — `getEntriesByIds` adds the **live cached**
`PkIndexEntry` to its read list (`toRead.add(pkIndex.get(pos))`, ~`:451`). The comment (~`:443-446`)
claims an "immutable snapshot" — this is **factually wrong**: `shiftPkPositionsAfterCompaction` mutates
`PkIndexEntry.position` in place.

**Impact:** under a normal read the collection read lock excludes concurrent writers, so it is safe (all
five audits confirmed normal reads have full snapshot isolation). Under an opt-in **dirty read**
(`"dirtyRead":true`, no collection lock) a concurrent delete/update compaction can shift the position
between resolution and the positioned read, so the read can land at a shifted offset or past EOF. Dirty
reads are documented as a weaker guarantee, but the comment overstates safety.

**Recommended fix:** copy each resolved `PkIndexEntry` into a detached `PkIndexEntry`
(value of position/length/page) before adding to `toRead`, making the comment true and closing the
dirty-read window. Also document that `streamCollection` on a dirty read can observe a relocated
document twice or zero times (the #7 delete-from-A + insert-into-B window).

---

## 🟡 LOW — `evictDatabase` prefix match over-evicts sibling databases

**Where:** `src/main/java/org/techhouse/cache/UserCache.java` — `evictDatabase` (~`:482-488`) filters
`collectionMap.keySet().stream().filter(s -> s.startsWith(dbName))`. Keys are `db|coll`, so dropping db
`foo` also matches `foobar|...`.

**Impact:** over-eviction of a sibling database's user cache (forces reload; not data loss).

**Recommended fix:** match on `dbName + Globals.COLL_IDENTIFIER_SEPARATOR` (as
`AdminCache.getCollectionNamesForDatabase` already does).

---

## 🟡 LOW — `collection_usage` event can write a row for a just-dropped collection

**Where:** `src/main/java/org/techhouse/ops/AdminOperationHelper.java` — `upsertCollectionUsage`
(~`:442-479`) skips only when `dbName == ADMIN_DB`; it does not check whether the collection still
exists. A usage event queued before a drop and processed after recreates a `collection_usage` row.

**Impact:** a stray usage row; self-heals via the hourly 24h prune. Low.

**Recommended fix:** skip the upsert when `getCollectionEntry(dbName, collName) == null` (mirroring the
entity-event vanished-collection guard in `EventProcessorHelper`).

---

## 🟡 LOW (fragile-but-currently-safe) — `file.length()` read outside the per-file write lock

**Where:** `src/main/java/org/techhouse/fs/FileSystem.java` — `deleteFromCollection` (~`:314`),
`updateFromCollection` (~`:428`), `bulkInsertIntoCollection` (~`:226`) read `file.length()` before
acquiring the per-file write lock.

**Impact:** currently safe **only** because every caller holds the collection write lock (the writer is
the sole mutator of the page file). It would silently become a torn-position bug if any future caller
invoked these without the collection write lock.

**Recommended fix (defensive):** move the `file.length()` read inside the per-file write lock.

---

## ✅ Verified SAFE during this audit (no action needed)

- Lock ordering / deadlock, including the databases→collections order and the
  `deleteDatabaseEntry`→`deleteCollectionEntry` reentrancy (`pages_*` is always the innermost lock).
- The grow-update relocation path runs entirely under the collection write lock.
- Per-file read/write locks cover every `.dat`/`.idx`/`pk.idx` access reachable from dirty reads.
- Eviction uses `tryLockWrite`, so it never races an active normal read (skips locked collections).
- The PK index file and the in-memory `pkIndexMap` never permanently diverge (PK index is synchronous;
  every compaction is applied to both disk and memory).
- Index-only COUNT fallback is exact, including mixed scalar+object/array conjunctions
  (`resolveConjunctionIds` returns `null` if any leaf is hash-resolved).
- NOR/NAND complement, synchronous create/drop-index vs concurrent saves, and the `pkIndexSize`
  completeness gate.

---

## Suggested order to tackle

1. **HIGH** — mixed-type GROUP_BY/SORT/DISTINCT (wrong query results).
2. **MED** — DROP_DATABASE admin-page leak (`deletePageCollections`).
3. **MED** — insert double-count (page-metadata accuracy).
4. **LOW/cheap** — `getEntriesByIds` detached copy + comment; `evictDatabase` prefix; `collection_usage`
   drop guard; `file.length()` inside lock.
