import json
import os
import time

import base_utils as bu
from fuzz.oracles import count_of, rows_of

from .workload import (ACCEPTED, BULK_SAVE, CHURNED_INDEX, CLOSED_MESSAGE, ID_OPS, OUTCOME_ERROR, OUTCOME_OK,
                       SEVERITY_FAIL, SEVERITY_OBSERVE, TRANSACTION, Divergence, History, WorkloadSchema)

FULL_SCAN = [{"type": "SKIP", "skip": 0}]
MAX_VALUES_PER_FIELD = 20
IDLE_DEADLINE_S = 60.0
NOBODY = "<a value nobody wrote>"
DUPLICATED = "<more than one live copy>"
LOG_REFUSALS = ("-FATAL-", "could be read as a", "refusing to append")
UNCLEAN_SUFFIX = "-indexes.unclean"
PROBE_ID = "durability_probe"


def page_files(work_dir: str, db: str, coll: str) -> list:
    folder = os.path.join(work_dir, "db", db, coll)
    if not os.path.isdir(folder):
        return []
    return sorted(f for f in os.listdir(folder) if f.endswith(".dat"))


def recorded_pages(work_dir: str, db: str, coll: str) -> dict:
    folder = os.path.join(work_dir, "db", "admin", "pages", f"{db}_{coll}")
    prefix = f"{db}|{coll}|"
    rows = {}
    if not os.path.isdir(folder):
        return rows
    for name in sorted(f for f in os.listdir(folder) if f.endswith(".dat")):
        with open(os.path.join(folder, name), "r", encoding="utf-8", errors="replace") as fp:
            for line in fp:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if str(row.get("_id", "")).startswith(prefix):
                    rows[int(row["page"])] = row
    return rows


def live_records(content: bytes) -> list:
    return [line + b"\n" for line in content.split(b"\n")[:-1] if line and not line.startswith(b" ")]


def actual_pages(work_dir: str, db: str, coll: str) -> dict:
    folder = os.path.join(work_dir, "db", db, coll)
    pages = {}
    for name in page_files(work_dir, db, coll):
        with open(os.path.join(folder, name), "rb") as fp:
            records = live_records(fp.read())
        pages[int(name[len(coll) + 1:-len(".dat")])] = (sum(len(record) for record in records), len(records))
    return pages


def occupancy_drift(recorded: dict, actual: dict, entries: bool = True) -> list:
    def drifted(page) -> bool:
        row = recorded.get(page)
        return row is None or row["size"] != actual[page][0] or (entries and row["entryCount"] != actual[page][1])

    def describe(page) -> str:
        size, count = actual[page]
        on_disk = f"on disk {size}B/{count} entries" if entries else f"on disk {size}B"
        row = recorded.get(page)
        if row is None:
            return f"page {page}: no recorded row, {on_disk}"
        if entries:
            return f"page {page}: recorded {row['size']}B/{row['entryCount']} entries, {on_disk}"
        return f"page {page}: recorded {row['size']}B, {on_disk}"

    return [describe(page) for page in sorted(actual) if drifted(page)]


def settled_drift(work_dir: str, db: str, coll: str, entries: bool = True, timeout_s: float = 10.0) -> list:
    def drift():
        return occupancy_drift(recorded_pages(work_dir, db, coll), actual_pages(work_dir, db, coll), entries)

    bu.wait_until(lambda: not drift(), timeout_s=timeout_s, interval_s=0.2)
    return drift()


def _aggregate(conn, db: str, coll: str, steps: list) -> dict:
    return conn.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll, "aggregationSteps": steps})


def observed_state(conn, schema: WorkloadSchema) -> dict:
    state = {}
    for coll in schema.collections:
        rows = rows_of(_aggregate(conn, schema.db, coll, FULL_SCAN))
        if rows is None:
            state[coll] = None
            continue
        copies = {}
        for row in rows:
            copies.setdefault(row.get("_id"), []).append(row)
        state[coll] = {doc_id: (held[0].get("w", NOBODY) if len(held) == 1 else DUPLICATED, held[0])
                       for doc_id, held in copies.items()}
    return state


def _token_of(state: dict, coll: str, doc_id: str):
    return state[coll].get(doc_id, (None, None))[0]


def _describe(entry) -> str:
    if entry is None:
        return "nothing"
    code = f"/{entry.error_code}" if entry.error_code else ""
    return f"{entry.op}@{entry.sent_at:.3f} {entry.outcome}{code}"


def acknowledged_durability(state: dict, history: History, schema: WorkloadSchema) -> list:
    found = []
    timelines = history.by_id()
    for coll in schema.collections:
        if state.get(coll) is None:
            found.append(Divergence("O1", history.seed, f"{coll}: the full scan failed, so no write can be checked"))
            continue
        for doc_id, (token, _) in sorted(state[coll].items()):
            if (coll, doc_id) not in timelines and doc_id != PROBE_ID:
                found.append(Divergence("O1", history.seed,
                                        f"{coll}/{doc_id}: present with {token!r}, but no write ever named it"))
    for (coll, doc_id), timeline in sorted(timelines.items()):
        if state.get(coll) is None:
            continue
        last_ok = max((i for i, (entry, _) in enumerate(timeline) if entry.outcome == OUTCOME_OK), default=-1)
        allowed = {timeline[last_ok][1]} if last_ok >= 0 else {None}
        allowed.update(token for _, token in timeline[last_ok + 1:])
        actual = _token_of(state, coll, doc_id)
        if actual not in allowed:
            acknowledged = timeline[last_ok][0] if last_ok >= 0 else None
            later = [_describe(entry) for entry, _ in timeline[last_ok + 1:]]
            found.append(Divergence("O1", history.seed,
                                    f"{coll}/{doc_id}: found {actual!r}, allowed {sorted(map(repr, allowed))};"
                                    f" last acknowledged {_describe(acknowledged)}, attempted after it {later}"))
    return found


def atomicity(state: dict, history: History, schema: WorkloadSchema) -> list:
    found = []
    entries = sorted(history.entries, key=lambda e: e.sent_at)
    for position, entry in enumerate(entries):
        if entry.op not in (TRANSACTION, BULK_SAVE) or entry.outcome == OUTCOME_OK or state.get(entry.coll) is None:
            continue
        written = [(doc_id, token) for doc_id, token in zip(entry.ids, entry.values) if token is not None]
        touched = set(entry.ids)
        if not written or any(later.coll == entry.coll and touched & set(later.ids)
                              for later in entries[position + 1:]):
            continue
        landed = sorted(doc_id for doc_id, token in written if _token_of(state, entry.coll, doc_id) == token)
        if 0 < len(landed) < len(written):
            severity = SEVERITY_FAIL if entry.op == TRANSACTION else SEVERITY_OBSERVE
            found.append(Divergence("O2", history.seed, f"{entry.coll}: {_describe(entry)} landed {len(landed)} of"
                                                         f" {len(written)} writes: {landed}", severity))
    return found


def _scalar_values(docs: list, field_name: str) -> list:
    seen = {}
    for doc in docs:
        value = doc.get(field_name)
        if value is None or isinstance(value, (dict, list)):
            continue
        seen.setdefault(json.dumps(value, sort_keys=True), value)
    return [seen[key] for key in sorted(seen)[:MAX_VALUES_PER_FIELD]]


def _id_multiset(response: dict):
    rows = rows_of(response)
    if rows is None:
        return "ERROR", response.get("errorCode") or response.get("status")
    return sorted(str(row.get("_id")) for row in rows)


def index_equals_scan(conn, state: dict, history: History, schema: WorkloadSchema) -> list:
    found = []
    for coll in schema.collections:
        if state.get(coll) is None:
            continue
        docs = [row for _, row in state[coll].values()]
        for field_name in list(schema.indexed_fields.get(coll, [])) + [CHURNED_INDEX]:
            for value in _scalar_values(docs, field_name):
                step = bu.filter_step(field_name, "EQUALS", value)
                indexed = _id_multiset(_aggregate(conn, schema.db, coll, [step]))
                scanned = _id_multiset(_aggregate(conn, schema.db, coll, FULL_SCAN + [step]))
                if indexed != scanned:
                    found.append(Divergence("O3", history.seed, f"{coll}: {field_name} EQUALS {value!r} answered"
                                                                 f" {indexed} by index and {scanned} by scan"))
        for doc_id in schema.ids.get(coll, []):
            response = conn.send({"type": "FIND_BY_ID", "databaseName": schema.db, "collectionName": coll,
                                  "_id": doc_id})
            point = (response.get("object") or {}).get("w", NOBODY) if response.get("status") == "OK" else None
            scanned = _token_of(state, coll, doc_id)
            if response.get("status") not in ("OK", "NOT_FOUND") or point != scanned:
                found.append(Divergence("O3", history.seed, f"{coll}/{doc_id}: FIND_BY_ID answered"
                                                             f" {response.get('status')} {point!r}, the scan"
                                                             f" {scanned!r}"))
    return found


def counts_agree(conn, state: dict, history: History, schema: WorkloadSchema) -> list:
    found = []
    for coll in schema.collections:
        if state.get(coll) is None:
            continue
        rows = len(state[coll])
        pk = count_of(_aggregate(conn, schema.db, coll, [{"type": "COUNT"}]))
        scanned = count_of(_aggregate(conn, schema.db, coll, FULL_SCAN + [{"type": "COUNT"}]))
        if not pk == scanned == rows:
            found.append(Divergence("O4", history.seed, f"{coll}: index-only COUNT {pk}, scanned COUNT {scanned},"
                                                         f" scanned rows {rows}"))
        if not schema.work_dir:
            continue
        drift = settled_drift(schema.work_dir, schema.db, coll)
        if drift:
            found.append(Divergence("O4", history.seed, f"{coll}: admin/pages never settled: {drift}"))
            continue
        recorded = sum(int(row.get("entryCount", 0))
                       for row in recorded_pages(schema.work_dir, schema.db, coll).values())
        if recorded != rows:
            found.append(Divergence("O4", history.seed,
                                    f"{coll}: admin/pages count {recorded} entries, the scan {rows}"))
    return found


def check_all(conn, history: History, schema: WorkloadSchema) -> list:
    state = observed_state(conn, schema)
    return (acknowledged_durability(state, history, schema) + atomicity(state, history, schema)
            + index_equals_scan(conn, state, history, schema) + counts_agree(conn, state, history, schema))


def background_idle(conn) -> bool:
    background = conn.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("background", {})
    return background.get("pending", 1) == 0 and background.get("maintenance", 1) == 0


def check_clean_start(log_path: str, conn, seed: int, work_dir: str = "", log_offset: int = 0,
                      deadline_s: float = IDLE_DEADLINE_S) -> list:
    found = []
    if not bu.wait_until(lambda: background_idle(conn), timeout_s=deadline_s, interval_s=0.25):
        found.append(Divergence("O5", seed, f"background work did not drain within {deadline_s:.0f} s"))
    for line in bu.read_log(log_path)[log_offset:].splitlines():
        if any(refusal in line for refusal in LOG_REFUSALS):
            found.append(Divergence("O5", seed, f"startup logged: {line[:400]}"))
    if work_dir:
        for root, _, files in os.walk(os.path.join(work_dir, "db")):
            for name in files:
                if name.endswith(UNCLEAN_SUFFIX):
                    found.append(Divergence("O5", seed, f"unclean index marker left: {os.path.join(root, name)}"))
    return found


def snapshot(conn, schema: WorkloadSchema) -> dict:
    state = observed_state(conn, schema)
    return {coll: None if state.get(coll) is None else {
        "docs": {doc_id: token for doc_id, (token, _) in state[coll].items()},
        "pk": count_of(_aggregate(conn, schema.db, coll, [{"type": "COUNT"}]))} for coll in schema.collections}


def _state_delta(before, after) -> str:
    if before is None or after is None:
        return f"{before!r} -> {after!r}"
    docs_before, docs_after = before["docs"], after["docs"]
    changed = {doc_id: (docs_before.get(doc_id), docs_after.get(doc_id))
               for doc_id in set(docs_before) | set(docs_after) if docs_before.get(doc_id) != docs_after.get(doc_id)}
    return f"pk {before['pk']} -> {after['pk']}, documents {dict(sorted(changed.items())[:10])}"


def check_idempotent(before: dict, conn, history: History, schema: WorkloadSchema) -> list:
    found = []
    after = snapshot(conn, schema)
    for coll in schema.collections:
        if before.get(coll) != after.get(coll):
            found.append(Divergence("O6", history.seed, f"{coll}: a second restart changed the state:"
                                                         f" {_state_delta(before.get(coll), after.get(coll))}"))
    found += check_all(conn, history, schema)
    for coll in schema.collections:
        saved = conn.send({"type": "SAVE", "databaseName": schema.db, "collectionName": coll,
                           "object": {"_id": PROBE_ID, "w": "probe"}})
        if saved.get("status") not in ACCEPTED:
            found.append(Divergence("O6", history.seed, f"{coll}: refused a write after recovery: {saved}"))
            continue
        conn.send({"type": "DELETE", "databaseName": schema.db, "collectionName": coll, "_id": PROBE_ID})
    return found


def failures(divergences: list) -> list:
    return [divergence for divergence in divergences if divergence.severity == SEVERITY_FAIL]


UNKNOWN_OUTCOME_CODES = ("503-8", "500-33")
RETRYABLE_CODES = ("503-16", "503-17")
UNCAUGHT_MARKER = "Exception in thread"
O7_PROBE_ID = PROBE_ID


def find_token(conn, db: str, coll: str, doc_id: str) -> tuple:
    answer = conn.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id})
    if answer.get("status") == "OK":
        return "present", (answer.get("object") or {}).get("w", NOBODY)
    if answer.get("status") == "NOT_FOUND":
        return "absent", None
    return "error", answer


def _send_within(connect, conn, request: dict, deadline: float) -> tuple:
    answer = {"status": "ERROR", "message": "no attempt fit the deadline"}
    while time.monotonic() < deadline:
        answer = conn.send(request, timeout=max(deadline - time.monotonic(), 0.5))
        if answer.get("message") == CLOSED_MESSAGE:
            conn.close()
            conn = connect()
            continue
        if answer.get("errorCode") in RETRYABLE_CODES:
            time.sleep(0.1)
            continue
        break
    if answer.get("message") == CLOSED_MESSAGE and time.monotonic() >= deadline:
        conn.close()
        conn = connect()
        answer = {"status": "NO_ANSWER", "message": "no answer before the deadline"}
    return conn, answer


def _probe_requests(db: str, coll: str, seed: int) -> list:
    def on(request: dict) -> dict:
        return {"databaseName": db, "collectionName": coll, **request}

    probe = {"_id": O7_PROBE_ID, "w": f"o7-{seed}"}
    return [
        ("SAVE", on({"type": "SAVE", "object": probe})),
        ("FIND_BY_ID", on({"type": "FIND_BY_ID", "_id": O7_PROBE_ID})),
        ("index-only COUNT", on({"type": "AGGREGATE", "aggregationSteps": [{"type": "COUNT"}]})),
        ("scanned COUNT", on({"type": "AGGREGATE", "aggregationSteps": FULL_SCAN + [{"type": "COUNT"}]})),
        ("START_TRANSACTION", {"type": "START_TRANSACTION"}),
        ("transactional SAVE", on({"type": "SAVE", "object": {**probe, "tx": True}})),
        ("COMMIT_TRANSACTION", {"type": "COMMIT_TRANSACTION"}),
        ("DELETE", on({"type": "DELETE", "_id": O7_PROBE_ID})),
    ]


def _probe_collection(connect, conn, db: str, coll: str, seed: int, lock_timeout_ms: int, divergences: list):
    in_transaction = False
    abandoned = False
    for label, request in _probe_requests(db, coll, seed):
        if label in ("transactional SAVE", "COMMIT_TRANSACTION") and abandoned:
            continue
        started = time.monotonic()
        conn, answer = _send_within(connect, conn, request, started + lock_timeout_ms / 1000.0)
        elapsed_ms = (time.monotonic() - started) * 1000.0
        ok = answer.get("status") in ACCEPTED
        if label == "START_TRANSACTION":
            in_transaction = ok
            abandoned = not ok
        elif label == "COMMIT_TRANSACTION":
            in_transaction = False
        if not ok:
            divergences.append(Divergence(
                "O7", seed, f"{db}|{coll}: {label} did not succeed within {lock_timeout_ms} ms ({elapsed_ms:.0f} ms):"
                            f" status={answer.get('status')} code={answer.get('errorCode')}"
                            f" message={str(answer.get('message'))[:200]!r}"))
        if in_transaction and not ok:
            conn.send({"type": "ROLLBACK_TRANSACTION"}, timeout=5.0)
            in_transaction = False
            abandoned = True
    return conn


def uncaught_exceptions(seed: int, server_output: str) -> list:
    return [Divergence("O7", seed, f"uncaught exception in the server output: {line[:300]}")
            for line in server_output.splitlines() if UNCAUGHT_MARKER in line]


def check_no_stranded_state(connect, schema: WorkloadSchema, seed: int, lock_timeout_ms: int,
                            server_output: str = "") -> list:
    divergences = []
    conn = connect()
    try:
        for coll in schema.collections:
            conn = _probe_collection(connect, conn, schema.db, coll, seed, lock_timeout_ms, divergences)
    finally:
        conn.close()
    return divergences + uncaught_exceptions(seed, server_output)


def _failed_for_sure(entry) -> bool:
    return entry.outcome == OUTCOME_ERROR and entry.error_code not in UNKNOWN_OUTCOME_CODES


def _explained(others: list, entry, token) -> bool:
    return token is None and any(other is not entry and other_token is None and not _failed_for_sure(other)
                                 for other, other_token in others)


def _created_before(others: list, entry) -> bool:
    return any(other.outcome == OUTCOME_OK and other_token is not None and other.sent_at < entry.sent_at
               for other, other_token in others)


def _survived(conn, schema: WorkloadSchema, entry, doc_id: str, token, others: list):
    state, found = find_token(conn, schema.db, entry.coll, doc_id)
    if state == "error":
        return None, f"FIND_BY_ID failed: {str(found)[:200]}"
    if token is None:
        return state == "absent" and _created_before(others, entry), "deleted"
    return state == "present" and found == token, token


def check_outcome_honesty(conn, history: History, schema: WorkloadSchema) -> list:
    divergences = []
    timelines = history.by_id()
    for entry in history.entries:
        if entry.op not in ID_OPS or not _failed_for_sure(entry) or str(entry.error_code).startswith("404"):
            continue
        survivors = []
        for doc_id, token in zip(entry.ids, entry.values):
            others = timelines.get((entry.coll, doc_id), [])
            if _explained(others, entry, token):
                continue
            survived, what = _survived(conn, schema, entry, doc_id, token, others)
            if survived is None:
                divergences.append(Divergence("O8", history.seed, f"{schema.db}|{entry.coll}|{doc_id}: {what}"
                                                                   f" while checking an error-answered {entry.op}"))
            elif survived:
                survivors.append(f"{doc_id}={what}")
        if not survivors:
            continue
        partial_bulk = entry.op == BULK_SAVE and len(survivors) < len(entry.ids)
        divergences.append(Divergence(
            "O8", history.seed, f"error-answered write survived: {_describe(entry)} on {schema.db}|{entry.coll}, yet"
                                f" {len(survivors)} of {len(entry.ids)} of its writes are there after a restart:"
                                f" {survivors[:10]}", SEVERITY_OBSERVE if partial_bulk else SEVERITY_FAIL))
    return divergences
