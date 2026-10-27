import json
import os
import random
import select
import threading
import time
from typing import Callable

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = os.environ.get("LISTEN_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("LISTEN_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "listen_test_db"
COLL = "listen_coll"

GEO_TARGET = "#geo(40.0,-74.0)"
GEO_NEAR = "#geo(40.001,-74.001)"
GEO_FAR = "#geo(34.05,-118.24)"
GEO_POLYGON = ["#geo(39.99,-74.02)", "#geo(39.99,-73.98)", "#geo(40.02,-73.98)", "#geo(40.02,-74.02)"]

VEC_QUERY = "#vector(1.0,0.0,0.0)"
VEC_CLOSE = "#vector(0.9,0.1,0.0)"
VEC_MID = "#vector(0.5,0.5,0.0)"
VEC_FAR = "#vector(0.0,0.0,1.0)"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def save_doc(conn: Conn, doc: dict) -> dict:
    return conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": doc})


def delete_doc(conn: Conn, id_: str) -> dict:
    return conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": id_})


def listen(conn: Conn, steps: list, coll: str = COLL) -> dict:
    return conn.send({"type": "LISTEN", "databaseName": DB, "collectionName": coll, "aggregationSteps": steps})


def stop_listen(conn: Conn, listen_id: str) -> dict:
    return conn.send({"type": "STOP_LISTEN", "listenId": listen_id})


def aggregate(conn: Conn, steps: list) -> dict:
    return conn.send({
        "type": "AGGREGATE",
        "databaseName": DB,
        "collectionName": COLL,
        "aggregationSteps": steps,
    })


def geo_distance_steps(comparator: str, distance: float, target: str = GEO_TARGET, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "distance", "field": field,
                                            "value": target, "comparator": comparator, "distance": distance}}]


def geo_within_steps(polygon, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "within", "field": field, "polygon": polygon}}]


def vector_nearest_steps(query: str, k: int, exact: bool = False, field: str = "embedding"):
    operator = {"customOperatorName": "nearest", "field": field, "value": query, "k": k}
    if exact:
        operator["exact"] = True
    return [{"type": "FILTER", "operator": operator}]


def setup(admin: Conn):
    admin.send({"type": "CREATE_DATABASE", "databaseName": DB})
    admin.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})


def teardown(admin: Conn):
    admin.send({"type": "DROP_DATABASE", "databaseName": DB})


def test_validation(admin: Conn):
    section("Validation: LISTEN and STOP_LISTEN")

    r = admin.send({"type": "LISTEN", "databaseName": DB, "collectionName": COLL})
    check_code("LISTEN without aggregationSteps → VALIDATION_ERROR", r, "ERROR", "400-1")

    r = admin.send({"type": "LISTEN", "collectionName": COLL, "aggregationSteps": []})
    check_code("LISTEN without databaseName → VALIDATION_ERROR", r, "ERROR", "400-1")

    r = admin.send({"type": "STOP_LISTEN"})
    check_code("STOP_LISTEN without listenId → VALIDATION_ERROR", r, "ERROR", "400-1")

    r = admin.send({"type": "STOP_LISTEN", "listenId": "not-a-uuid"})
    check_code("STOP_LISTEN with invalid UUID → VALIDATION_ERROR", r, "ERROR", "400-1")

    r = admin.send({"type": "STOP_LISTEN", "listenId": "00000000-0000-0000-0000-000000000000"})
    check_code("STOP_LISTEN with unknown UUID → NOT_FOUND", r, "NOT_FOUND", "404-7")


def test_initial_response(admin: Conn):
    section("LISTEN: initial response and result hash")

    save_doc(admin, {"_id": "init-1", "score": 10})

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "score", "value": 10}}]
    r = listen(admin, steps)
    check_status("LISTEN returns OK", r, "OK")

    listen_id = r.get("listenId")
    check("listenId is present", listen_id is not None, f"listenId={listen_id!r}")
    check("results is a list", isinstance(r.get("results"), list),
               f"results={r.get('results')!r}")
    check("resultHash is a 64-char hex string",
               isinstance(r.get("resultHash"), str) and len(r.get("resultHash", "")) == 64,
               f"resultHash={r.get('resultHash')!r}")
    check("initial results contain seeded document",
               any(d.get("_id") == "init-1" for d in (r.get("results") or [])),
               "missing init-1 from initial results")

    stop_listen(admin, listen_id)
    delete_doc(admin, "init-1")


def test_no_push_on_unrelated_write(admin: Conn):
    section("LISTEN: no push when unrelated collection changes")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "no-push-kind"}}]
    r = listen(admin, steps)
    check_status("LISTEN registered for no-push test", r, "OK")
    listen_id = r.get("listenId")

    admin.send({"type": "LIST_DATABASES"})

    pushed = admin.recv(timeout=1.5)
    check("no push received on unrelated activity", pushed is None,
               f"unexpected push: {pushed!r}")

    stop_listen(admin, listen_id)


def test_push_on_matching_insert(writer: Conn, listener: Conn):
    section("LISTEN: push received when new document matches query")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "watched"}}]
    r = listen(listener, steps)
    check_status("LISTEN registered", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    save_doc(writer, {"_id": "watched-1", "kind": "watched"})

    pushed = listener.recv(timeout=5.0)
    check("push received after matching insert", pushed is not None,
               "no push message within 5 s")

    if pushed is not None:
        check_status("push has OK status", pushed, "OK")
        check("push listenId matches", pushed.get("listenId") == listen_id,
                   f"expected {listen_id!r}, got {pushed.get('listenId')!r}")
        check("push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check("push results contain inserted document",
                   any(d.get("_id") == "watched-1" for d in (pushed.get("results") or [])),
                   "watched-1 missing from pushed results")

    stop_listen(listener, listen_id)
    delete_doc(writer, "watched-1")


def test_no_push_on_non_matching_insert(writer: Conn, listener: Conn):
    section("LISTEN: no push when insert does not affect query results")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "important"}}]
    r = listen(listener, steps)
    check_status("LISTEN registered for non-match test", r, "OK")
    listen_id = r.get("listenId")

    save_doc(writer, {"_id": "irrelevant-1", "kind": "unimportant"})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check("no push for non-matching insert", pushed is None,
               f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "irrelevant-1")


def test_push_on_delete(writer: Conn, listener: Conn):
    section("LISTEN: push received when matching document is deleted")

    save_doc(writer, {"_id": "del-target", "kind": "deletable"})
    time.sleep(0.3)

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "deletable"}}]
    r = listen(listener, steps)
    check_status("LISTEN registered for delete test", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    delete_doc(writer, "del-target")

    pushed = listener.recv(timeout=5.0)
    check("push received after delete", pushed is not None,
               "no push message within 5 s")

    if pushed is not None:
        check("push resultHash differs after delete",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check("push results no longer contain deleted document",
                   not any(d.get("_id") == "del-target" for d in (pushed.get("results") or [])),
                   "del-target still present in pushed results")

    stop_listen(listener, listen_id)


def test_push_on_geo_distance_match(writer: Conn, listener: Conn):
    section("LISTEN: push received when geo distance query matches (JsonGeo)")

    steps = geo_distance_steps("SMALLER_THAN", 5000, GEO_TARGET)
    r = listen(listener, steps)
    check_status("LISTEN registered for geo distance", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    save_doc(writer, {"_id": "geo-near-1", "location": GEO_NEAR})

    pushed = listener.recv(timeout=5.0)
    check("push received after nearby insert", pushed is not None,
               "no push message within 5 s")
    if pushed is not None:
        check_status("geo distance push has OK status", pushed, "OK")
        check("geo distance push listenId matches", pushed.get("listenId") == listen_id,
                   f"expected {listen_id!r}, got {pushed.get('listenId')!r}")
        check("geo distance push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check("geo distance push results contain nearby doc",
                   any(d.get("_id") == "geo-near-1" for d in (pushed.get("results") or [])),
                   "geo-near-1 missing from pushed results")

    stop_listen(listener, listen_id)
    delete_doc(writer, "geo-near-1")


def test_no_push_on_geo_distance_non_match(writer: Conn, listener: Conn):
    section("LISTEN: no push when geo distance query does not match (JsonGeo)")

    steps = geo_distance_steps("SMALLER_THAN", 5000, GEO_TARGET)
    r = listen(listener, steps)
    check_status("LISTEN registered for geo distance non-match", r, "OK")
    listen_id = r.get("listenId")

    save_doc(writer, {"_id": "geo-far-1", "location": GEO_FAR})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check("no push for far-away insert", pushed is None,
               f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "geo-far-1")


def test_push_on_geo_within_match(writer: Conn, listener: Conn):
    section("LISTEN: push on geo within-polygon match, none for outside (JsonGeo)")

    steps = geo_within_steps(GEO_POLYGON)
    r = listen(listener, steps)
    check_status("LISTEN registered for geo within", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    save_doc(writer, {"_id": "geo-in-1", "location": GEO_NEAR})

    pushed = listener.recv(timeout=5.0)
    check("push received after in-polygon insert", pushed is not None,
               "no push message within 5 s")
    if pushed is not None:
        check_status("geo within push has OK status", pushed, "OK")
        check("geo within push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check("geo within push results contain in-polygon doc",
                   any(d.get("_id") == "geo-in-1" for d in (pushed.get("results") or [])),
                   "geo-in-1 missing from pushed results")

    save_doc(writer, {"_id": "geo-out-1", "location": GEO_FAR})
    time.sleep(0.5)

    pushed_2 = listener.recv(timeout=2.0)
    check("no push for out-of-polygon insert", pushed_2 is None,
               f"unexpected push: {pushed_2!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "geo-in-1")
    delete_doc(writer, "geo-out-1")


def test_aggregate_vector_nearest(conn: Conn):
    section("AGGREGATE: vector nearest returns top-K ordered by similarity (JsonVector)")

    save_doc(conn, {"_id": "vec-a", "embedding": VEC_CLOSE})
    save_doc(conn, {"_id": "vec-b", "embedding": VEC_MID})
    save_doc(conn, {"_id": "vec-c", "embedding": VEC_FAR})

    r = aggregate(conn, vector_nearest_steps(VEC_QUERY, 2))
    check_status("AGGREGATE nearest returns OK", r, "OK")
    ids = [d.get("_id") for d in (r.get("results") or [])]
    check("nearest top-2 are the two most similar, ordered", ids == ["vec-a", "vec-b"],
               f"got {ids!r}")

    r_exact = aggregate(conn, vector_nearest_steps(VEC_QUERY, 2, exact=True))
    ids_exact = [d.get("_id") for d in (r_exact.get("results") or [])]
    check("nearest exact top-2 match the approximate result", ids_exact == ["vec-a", "vec-b"],
               f"got {ids_exact!r}")

    delete_doc(conn, "vec-a")
    delete_doc(conn, "vec-b")
    delete_doc(conn, "vec-c")


def test_push_on_vector_nearest_closer(writer: Conn, listener: Conn):
    section("LISTEN: push when a closer vector enters the top-K (JsonVector)")

    save_doc(writer, {"_id": "vec-seed-far", "embedding": VEC_FAR})
    time.sleep(0.5)

    r = listen(listener, vector_nearest_steps(VEC_QUERY, 1))
    check_status("LISTEN registered for vector nearest", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    save_doc(writer, {"_id": "vec-closer", "embedding": VEC_CLOSE})

    pushed = listener.recv(timeout=5.0)
    check("push received after closer vector insert", pushed is not None, "no push within 5 s")
    if pushed is not None:
        check_status("vector nearest push has OK status", pushed, "OK")
        check("vector nearest push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash, f"hash unchanged: {pushed.get('resultHash')!r}")
        check("vector nearest push top-1 is the closer vector",
                   [d.get("_id") for d in (pushed.get("results") or [])] == ["vec-closer"],
                   f"unexpected results: {pushed.get('results')!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "vec-seed-far")
    delete_doc(writer, "vec-closer")


def test_no_push_on_vector_farther(writer: Conn, listener: Conn):
    section("LISTEN: no push when a farther vector stays outside the top-K (JsonVector)")

    save_doc(writer, {"_id": "vec-top", "embedding": VEC_CLOSE})
    time.sleep(0.5)

    r = listen(listener, vector_nearest_steps(VEC_QUERY, 1))
    check_status("LISTEN registered for vector nearest non-match", r, "OK")
    listen_id = r.get("listenId")

    save_doc(writer, {"_id": "vec-farther", "embedding": VEC_FAR})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check("no push for farther vector", pushed is None, f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "vec-top")
    delete_doc(writer, "vec-farther")


def check_reorder_pushes(writer: Conn, listener: Conn, seeds: list, steps: list, key: str, labels: dict,
                         initial: list, reorder: dict, reordered: list,
                         no_push_detail: str = "no push message within 5 s"):
    for doc in seeds:
        save_doc(writer, doc)
    time.sleep(0.5)

    r = listen(listener, steps)
    check_status(labels["registered"], r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")
    got = [d.get(key) for d in (r.get("results") or [])]
    check(labels["initial"], got == initial, f"got {got!r}")

    save_doc(writer, reorder)

    pushed = listener.recv(timeout=5.0)
    check(labels["push"], pushed is not None, no_push_detail)

    if pushed is not None:
        check("push resultHash differs from initial",
              pushed.get("resultHash") != initial_hash,
              f"hash unchanged: {pushed.get('resultHash')!r}")
        got = [d.get(key) for d in (pushed.get("results") or [])]
        check(labels["pushed"], got == reordered, f"got {got!r}")

    stop_listen(listener, listen_id)
    for doc in seeds:
        delete_doc(writer, doc["_id"])


def test_push_on_reorder_inside_a_sorted_top_k(writer: Conn, listener: Conn):
    section("LISTEN: push when a SORT + LIMIT top-K is reordered without changing its members")
    check_reorder_pushes(
        writer, listener,
        seeds=[{"_id": "topk-a", "kind": "topk", "score": 5},
               {"_id": "topk-b", "kind": "topk", "score": 3},
               {"_id": "topk-c", "kind": "topk", "score": 1}],
        steps=[{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "topk"}},
               {"type": "SORT", "fieldName": "score", "ascending": False},
               {"type": "LIMIT", "limit": 2}],
        key="_id",
        labels={"registered": "LISTEN registered for the sorted top-K",
                "initial": "initial top-K is [topk-a, topk-b]",
                "push": "push received after the top-K was reordered",
                "pushed": "pushed top-K is [topk-b, topk-a]"},
        initial=["topk-a", "topk-b"],
        reorder={"_id": "topk-b", "kind": "topk", "score": 9},
        reordered=["topk-b", "topk-a"])


def test_push_on_reorder_inside_an_unsorted_nearest_top_k(writer: Conn, listener: Conn):
    section("LISTEN: push when a nearest top-K with no trailing SORT is reordered without changing its members")
    check_reorder_pushes(
        writer, listener,
        seeds=[{"_id": "nearest-topk-a", "embedding": "#vector(0.9,0.1,0.0)"},
               {"_id": "nearest-topk-b", "embedding": "#vector(0.8,0.2,0.0)"},
               {"_id": "nearest-topk-c", "embedding": VEC_FAR}],
        steps=vector_nearest_steps(VEC_QUERY, 2),
        key="_id",
        labels={"registered": "LISTEN registered for the unsorted nearest top-K",
                "initial": "initial nearest top-2 is [nearest-topk-a, nearest-topk-b]",
                "push": "push received after the nearest top-K was reordered",
                "pushed": "pushed nearest top-2 is [nearest-topk-b, nearest-topk-a]"},
        initial=["nearest-topk-a", "nearest-topk-b"],
        reorder={"_id": "nearest-topk-b", "embedding": "#vector(0.95,0.05,0.0)"},
        reordered=["nearest-topk-b", "nearest-topk-a"])


def test_push_on_reorder_after_a_sort_then_distinct(writer: Conn, listener: Conn):
    section("LISTEN: push when a SORT followed by DISTINCT is reordered without changing its members")
    check_reorder_pushes(
        writer, listener,
        seeds=[{"_id": "dist-a", "kind": "distinct", "category": "alpha", "score": 1},
               {"_id": "dist-b", "kind": "distinct", "category": "beta", "score": 2}],
        steps=[{"type": "FILTER",
                "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "distinct"}},
               {"type": "SORT", "fieldName": "score", "ascending": True},
               {"type": "DISTINCT", "fieldName": "category"}],
        key="category",
        labels={"registered": "LISTEN registered for the sorted distinct query",
                "initial": "initial result is [alpha, beta]",
                "push": "push received after the distinct values were reordered",
                "pushed": "pushed result is [beta, alpha]"},
        initial=["alpha", "beta"],
        reorder={"_id": "dist-b", "kind": "distinct", "category": "beta", "score": 0},
        reordered=["beta", "alpha"],
        no_push_detail="no push message within 5 s: a DISTINCT after a SORT keeps the sorted order, "
                       "so a reorder is a change")


def test_no_push_when_an_unordered_group_by_result_is_unchanged(writer: Conn, listener: Conn):
    section("LISTEN: no push when a GROUP_BY result set is unchanged")

    save_doc(writer, {"_id": "grp-a", "kind": "grouped", "category": "books"})
    save_doc(writer, {"_id": "grp-b", "kind": "grouped", "category": "books"})
    time.sleep(0.5)

    steps = [
        {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "grouped"}},
        {"type": "SORT", "fieldName": "category", "ascending": True},
        {"type": "GROUP_BY", "fieldName": "category"},
    ]
    r = listen(listener, steps)
    check_status("LISTEN registered for the grouped query", r, "OK")
    listen_id = r.get("listenId")

    save_doc(writer, {"_id": "grp-c", "kind": "other", "category": "music"})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check("no push for a write outside the grouped result", pushed is None,
          f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    for id_ in ("grp-a", "grp-b", "grp-c"):
        delete_doc(writer, id_)


def test_stop_listen(writer: Conn, listener: Conn):
    section("LISTEN: STOP_LISTEN cancels subscription")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "stoppable"}}]
    r = listen(listener, steps)
    check_status("LISTEN registered for stop test", r, "OK")
    listen_id = r.get("listenId")

    r_stop = stop_listen(listener, listen_id)
    check_status("STOP_LISTEN returns OK", r_stop, "OK")

    save_doc(writer, {"_id": "stoppable-1", "kind": "stoppable"})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check("no push after STOP_LISTEN", pushed is None,
               f"unexpected push: {pushed!r}")

    delete_doc(writer, "stoppable-1")


def test_stop_listen_from_another_client_is_refused(writer: Conn, listener: Conn):
    section("LISTEN: STOP_LISTEN only cancels the caller's own listener")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "owned"}}]
    r = listen(listener, steps)
    check_status("LISTEN registered for the ownership test", r, "OK")
    listen_id = r.get("listenId")

    with Conn() as attacker:
        attacker.authenticate()
        r_stolen = attacker.send({"type": "STOP_LISTEN", "listenId": listen_id})
        check_code("another client cannot stop this listener", r_stolen, "NOT_FOUND", "404-7")

    save_doc(writer, {"_id": "owned-1", "kind": "owned"})
    pushed = listener.recv(timeout=5.0)
    check("the owner keeps receiving pushes", pushed is not None, "the listener was cancelled by another client")

    check_status("the owner can still stop its own listener", stop_listen(listener, listen_id), "OK")
    delete_doc(writer, "owned-1")


def test_multiple_listeners(writer: Conn, listener: Conn):
    section("LISTEN: multiple listeners on same collection")

    steps_a = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "tag", "value": "alpha"}}]
    steps_b = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "tag", "value": "beta"}}]

    r_a = listen(listener, steps_a)
    check_status("LISTEN A registered", r_a, "OK")
    listen_id_a = r_a.get("listenId")

    with Conn() as l2:
        auth_r = l2.send({"type": "AUTHENTICATE", "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
        check_status("Listener-2 authenticated", auth_r, "OK")

        r_b = l2.send({
            "type": "LISTEN",
            "databaseName": DB,
            "collectionName": COLL,
            "aggregationSteps": steps_b,
        })
        check_status("LISTEN B registered", r_b, "OK")
        listen_id_b = r_b.get("listenId")

        save_doc(writer, {"_id": "alpha-doc", "tag": "alpha"})

        pushed_a = listener.recv(timeout=5.0)
        check("Listener A received push for alpha doc", pushed_a is not None,
                   "no push to listener A")

        pushed_b = l2.recv(timeout=2.0)
        check("Listener B did NOT receive push for alpha doc", pushed_b is None,
                   f"unexpected push to B: {pushed_b!r}")

        stop_listen(listener, listen_id_a)
        l2.send({"type": "STOP_LISTEN", "listenId": listen_id_b})
        delete_doc(writer, "alpha-doc")


def test_transactional_commit_pushes_once_with_the_final_state(writer: Conn, listener: Conn):
    section("LISTEN: a transactional commit notifies once, with every op applied")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "txn"}}]
    r = listen(listener, steps)
    check_status("LISTEN registered for the transaction test", r, "OK")
    listen_id = r.get("listenId")

    expected = {f"txn-{i}" for i in range(1, 6)}
    check_status("START_TRANSACTION", writer.send({"type": "START_TRANSACTION"}), "OK")
    for id_ in sorted(expected):
        save_doc(writer, {"_id": id_, "kind": "txn"})
    check_status("COMMIT_TRANSACTION", writer.send({"type": "COMMIT_TRANSACTION"}), "OK")

    pushed = listener.recv(timeout=5.0)
    check("push received after the commit", pushed is not None, "no push message within 5 s")
    if pushed is not None:
        ids = {d.get("_id") for d in (pushed.get("results") or [])}
        check("the pushed frame holds every document the transaction wrote", ids == expected,
              f"partially applied frame pushed: {sorted(ids)}")

    extra = listener.recv(timeout=1.5)
    check("no second frame for the same commit", extra is None, f"unexpected extra push: {extra!r}")

    stop_listen(listener, listen_id)
    for id_ in sorted(expected):
        delete_doc(writer, id_)

def test_disconnect_cleanup(writer: Conn):
    section("LISTEN: listener cleanup on client disconnect")

    with Conn() as tmp:
        tmp.authenticate()
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "cleanup"}}]
        r = listen(tmp, steps)
        check_status("Temporary LISTEN registered", r, "OK")
        listen_id = r.get("listenId")

    time.sleep(0.5)

    r_stop = stop_listen(writer, listen_id)
    check_code("STOP_LISTEN after disconnect → NOT_FOUND", r_stop, "NOT_FOUND", "404-7")


BULKY_DOCS = 20
BULKY_WRITES = 60


def bulky_doc(i: int) -> dict:
    return {"_id": f"bulky-{i % BULKY_DOCS}", "kind": "bulky", "n": i, "blob": "x" * 50_000}


def test_a_non_reading_listener_is_disconnected_and_others_still_notified(writer: Conn):
    section("LISTEN: a listener that stops reading is cut off; the others keep getting pushes")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "bulky"}}]
    for i in range(BULKY_DOCS):
        save_doc(writer, bulky_doc(i))
    slow = bu.non_reading_conn()
    with Conn() as fast:
        fast.authenticate()
        check_status("the slow listener registers", listen(slow, steps), "OK")
        r_fast = listen(fast, steps)
        check_status("the reading listener registers", r_fast, "OK")

        missed = []
        for i in range(BULKY_DOCS, BULKY_DOCS + BULKY_WRITES):
            save_doc(writer, bulky_doc(i))
            pushed = fast.recv(timeout=10.0)
            if pushed is None:
                missed.append(i)
                break
        check("every write still reached the reading listener", not missed,
              f"writes the reading listener never heard about: {missed}")

        check("the slow listener's connection is closed once its backlog overflows",
              bu.read_until_closed(slow, timeout=30.0), "the server still held the connection open")
        stop_listen(fast, r_fast.get("listenId"))
    slow.close()
    for i in range(BULKY_DOCS):
        delete_doc(writer, f"bulky-{i}")


def test_a_connection_cannot_register_past_its_listen_limit():
    section("LISTEN: one connection is capped at maxListensPerConnection registrations")

    per_connection_limit = 64
    with Conn() as tmp:
        tmp.authenticate()
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "capped"}}]
        statuses = [listen(tmp, steps).get("status") for _ in range(per_connection_limit)]
        check(f"The first {per_connection_limit} registrations succeed", statuses.count("OK") == per_connection_limit,
              f"statuses={statuses}")
        check_code("The next one -> 400-25", listen(tmp, steps), "ERROR", "400-25")
    with Conn() as fresh:
        fresh.authenticate()
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "capped"}}]
        check_status("Another connection still registers", listen(fresh, steps), "OK")


LISTEN_MAX_REQUEST_BYTES = 1024 * 1024
LISTENS_PER_CONNECTION = 64
LISTENS_PER_USER = 256


def wait_for_status(attempt: Callable[[], dict], expected: str, timeout: float = 5.0) -> dict:
    deadline = time.time() + timeout
    response = attempt()
    while response.get("status") != expected and time.time() < deadline:
        time.sleep(0.1)
        response = attempt()
    return response


def test_oversized_listen_is_400_37(admin: Conn):
    section("LISTEN: a request line over listenMaxRequestBytes answers 400-37 before it runs")

    operand = "o" * 300
    values = [f"{i}-{operand}" for i in range(LISTEN_MAX_REQUEST_BYTES // len(operand) + 100)]
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "kind", "value": values}}]
    check_code("LISTEN with a ~1.1Mb IN list -> 400-37", listen(admin, steps), "ERROR", "400-37")
    small = [{"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "kind", "value": values[:100]}}]
    r = listen(admin, small)
    check_status("The same connection still registers a small LISTEN", r, "OK")
    stop_listen(admin, r.get("listenId"))


def fill_per_user_cap(connections: list, steps: list) -> list:
    responses = []
    for conn in connections:
        for _ in range(LISTENS_PER_CONNECTION):
            response = listen(conn, steps)
            responses.append(response)
            if response.get("status") != "OK":
                return responses
    return responses


def test_listens_per_user_cap_across_connections_is_400_38():
    section("LISTEN: one user is capped at maxListensPerUser registrations across connections")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "peruser"}}]
    connections = [Conn() for _ in range(LISTENS_PER_USER // LISTENS_PER_CONNECTION + 1)]
    try:
        for conn in connections:
            conn.authenticate()
        responses = fill_per_user_cap(connections, steps)
        accepted = sum(1 for r in responses if r.get("status") == "OK")
        check(f"At most {LISTENS_PER_USER} registrations are accepted across the connections",
              LISTENS_PER_USER - 8 <= accepted <= LISTENS_PER_USER, f"accepted={accepted}")
        check_code("The next one, on a connection under its own limit -> 400-38", responses[-1], "ERROR", "400-38")
    finally:
        for conn in connections:
            conn.close()


def test_retained_budget_released_on_disconnect():
    section("LISTEN: a closed connection gives its registrations back to the user's allowance")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "released"}}]
    connections = [Conn() for _ in range(LISTENS_PER_USER // LISTENS_PER_CONNECTION + 1)]
    try:
        for conn in connections:
            conn.authenticate()
        responses = fill_per_user_cap(connections, steps)
        check_code("The user is at its cap", responses[-1], "ERROR", "400-38")
        connections[0].close()
        survivor = connections[-1]
        r = wait_for_status(lambda: listen(survivor, steps), "OK")
        check_status("Once a holding connection closes, the user registers again", r, "OK")
    finally:
        for conn in connections:
            conn.close()


def test_a_listen_whose_map_exceeds_the_entry_size_answers_400_28(admin: Conn):
    section("LISTEN: a MAP refused for its result size answers its own code")

    operators = [{"fieldName": "g0", "operator": {"type": "CONCAT", "operands": ["-AAAAAAAA", "-AAAAAAAA"]}}]
    operators += [{"fieldName": f"g{i}", "operator": {"type": "CONCAT", "operands": [f"g{i - 1}", f"g{i - 1}"]}}
                  for i in range(1, 31)]
    save_doc(admin, {"_id": "concat-seed", "kind": "concat"})
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "concat"}},
             {"type": "MAP", "operators": operators}]
    check_code("LISTEN with a doubling CONCAT -> 400-28", listen(admin, steps), "ERROR", "400-28")
    delete_doc(admin, "concat-seed")


def test_unauthenticated_listen():
    section("LISTEN: unauthenticated client is rejected")

    with Conn() as c:
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "x"}}]
        r = listen(c, steps)
        check_code("LISTEN without auth → MUST_AUTHENTICATE_FIRST", r, "UNAUTHENTICATED", "401-1")


def check_listen_ended(label: str, pushed, listen_id: str) -> bool:
    if pushed is None:
        return check(label, False, "no frame within the timeout")
    return check(label, pushed.get("errorCode") == "410-1" and pushed.get("status") == "NOT_FOUND"
                 and pushed.get("listenId") == listen_id, f"got: {pushed!r}")


def test_a_revoked_reader_is_told_its_listen_ended(writer: Conn):
    section("LISTEN: a reader whose permission was revoked is told its listen ended")
    reader_name = "listen_revoked_reader"
    writer.send({"type": "DELETE_USER", "username": reader_name})
    check_status("create a reader on the listen database", writer.send({
        "type": "CREATE_USER", "username": reader_name, "password": "listen_reader1234", "admin": False,
        "globalPermissions": [], "databasePermissions": {DB: "READ"}, "collectionPermissions": {}}), "OK")
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "revoked"}}]
    try:
        with Conn() as reader:
            check_status("AUTHENTICATE as the reader", reader.authenticate(reader_name, "listen_reader1234"), "OK")
            r = listen(reader, steps)
            check_status("the reader may LISTEN while it holds READ", r, "OK")
            listen_id = r.get("listenId")

            save_doc(writer, {"_id": "revoked-1", "kind": "revoked"})
            pushed = reader.recv(timeout=5.0)
            check("the reader is pushed a matching write while authorized", pushed is not None,
                  "no push within 5 s")

            check_status("revoke the reader's database permission", writer.send({
                "type": "CHANGE_PERMISSIONS", "username": reader_name, "admin": False, "globalPermissions": [],
                "databasePermissions": {}, "collectionPermissions": {}}), "OK")
            save_doc(writer, {"_id": "revoked-2", "kind": "revoked"})
            check_listen_ended("the revoked reader is told its listen ended", reader.recv(timeout=5.0), listen_id)
            pushed = reader.recv(timeout=2.0)
            check("no data reaches a reader after its permission was revoked", pushed is None,
                  f"unexpected push: {pushed!r}")
            check_status("the revoked reader's own query is refused too", aggregate(reader, steps), "FORBIDDEN")
    finally:
        delete_doc(writer, "revoked-1")
        delete_doc(writer, "revoked-2")
        writer.send({"type": "DELETE_USER", "username": reader_name})


def test_dropping_the_collection_ends_the_listen(writer: Conn, listener: Conn):
    section("LISTEN: dropping the listened collection ends the listen with one terminal frame")
    coll = "listen_dropped_coll"
    writer.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    r = listen(listener, [], coll)
    check_status("LISTEN registered on the collection about to be dropped", r, "OK")
    listen_id = r.get("listenId")
    try:
        check_status("DROP_COLLECTION", writer.send(
            {"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
        check_listen_ended("the listener is told its listen ended", listener.recv(timeout=5.0), listen_id)
        check_code("the ended listen is no longer registered", stop_listen(listener, listen_id), "NOT_FOUND",
                   "404-7")

        writer.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
        writer.send({"type": "SAVE", "databaseName": DB, "collectionName": coll, "object": {"_id": "after"}})
        pushed = listener.recv(timeout=2.0)
        check("a re-created collection does not revive the ended listen", pushed is None,
              f"unexpected push: {pushed!r}")
    finally:
        writer.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": coll})


def test_dropping_a_joined_collection_ends_the_listen(writer: Conn, listener: Conn):
    section("LISTEN: dropping a JOIN target ends the listen on the primary collection")
    joined = "listen_joined_coll"
    writer.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": joined})
    steps = [{"type": "JOIN", "joinCollection": joined, "localField": "ref", "remoteField": "_id",
              "asField": "joined"}]
    r = listen(listener, steps)
    check_status("LISTEN with a JOIN registered", r, "OK")
    listen_id = r.get("listenId")
    try:
        check_status("DROP_COLLECTION of the joined collection", writer.send(
            {"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": joined}), "OK")
        check_listen_ended("the JOIN listener is told its listen ended", listener.recv(timeout=5.0), listen_id)
        pushed = listener.recv(timeout=2.0)
        check("exactly one terminal frame arrives", pushed is None, f"unexpected push: {pushed!r}")
    finally:
        writer.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": joined})


def test_listener_survives_a_concurrent_bulk_save(writer_conn: Conn, listener_conn: Conn):
    section("A re-run during a bulk save still answers correctly")
    started = listen(listener_conn, [{"type": "FILTER",
                                      "operator": {"fieldOperatorType": "EQUALS",
                                                   "field": "kind", "value": "bulk"}}])
    listen_id = started.get("listenId")
    check("listener registered for the bulk case", listen_id is not None, detail=str(started))

    expected = 40
    objects = [{"_id": f"bulk_{i:03d}", "kind": "bulk"} for i in range(expected)]
    writer_conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
                      "objects": objects})

    seen = [-1]

    def _converged_or_quiet():
        pushed = listener_conn.recv(timeout=5.0)
        if not pushed:
            return True
        seen[0] = len(pushed.get("results") or [])
        return seen[0] == expected

    bu.wait_until(_converged_or_quiet, 15.0, 0)
    check("a listener converges on the full bulk result rather than a torn read",
          seen[0] == expected, detail=f"last pushed count={seen[0]} expected={expected}")

    stop_listen(listener_conn, listen_id)
    for i in range(expected):
        delete_doc(writer_conn, f"bulk_{i:03d}")


HEX = set("0123456789abcdef")


def is_64_hex(value) -> bool:
    return isinstance(value, str) and len(value) == 64 and set(value) <= HEX


def test_a_pushed_frame_parses_with_its_own_listen_id_and_a_64_hex_hash(writer: Conn, listener: Conn):
    section("LISTEN: every pushed frame is strict JSON carrying its own listenId and a 64-hex hash")
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "framed"}}]
    first = listen(listener, steps)
    second = listen(listener, steps)
    check_status("two identical listens register on one connection", first, "OK")
    check_status("the second identical listen registers", second, "OK")
    ids = {first.get("listenId"), second.get("listenId")}
    save_doc(writer, {"_id": "framed-1", "kind": "framed", "text": "quote\" 日本 \"results\":[]"})
    frames = []
    for _ in range(2):
        listener.s.settimeout(5.0)
        raw = listener.f.readline().decode()
        try:
            frames.append(json.loads(raw, parse_constant=bu._refuse_non_rfc_constant))
        except (json.JSONDecodeError, ValueError) as error:
            check("a pushed frame parses as strict JSON", False, f"{error}: {raw[:200]!r}")
    check("each listen got exactly one frame with its own listenId",
          sorted(frame.get("listenId") for frame in frames) == sorted(ids), str(frames)[:300])
    for frame in frames:
        check("the pushed frame is an OK LISTEN update",
              frame.get("type") == "LISTEN" and frame.get("status") == "OK" and frame.get("isUpdate") is True,
              str(frame)[:200])
        check("the pushed hash is 64 lowercase hex characters", is_64_hex(frame.get("resultHash")),
              str(frame.get("resultHash")))
        check("the pushed results carry the written document unchanged",
              [row.get("text") for row in frame.get("results") or []] == ["quote\" 日本 \"results\":[]"],
              str(frame.get("results"))[:200])
    for listen_id in ids:
        stop_listen(listener, listen_id)
    delete_doc(writer, "framed-1")


def test_identical_listeners_on_many_connections_each_get_their_own_push(writer: Conn):
    section("LISTEN: identical listens on many connections each get their own push")
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "shared"}}]
    other_steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind",
                                                   "value": "not-shared"}}]
    listeners = [Conn.authenticated() for _ in range(11)]
    try:
        registered = [listen(conn, steps if i < 10 else other_steps) for i, conn in enumerate(listeners)]
        check("all eleven listens register", all(r.get("status") == "OK" for r in registered),
              str([r.get("status") for r in registered]))
        save_doc(writer, {"_id": "shared-1", "kind": "shared"})
        pushes = [conn.recv(timeout=5.0) for conn in listeners[:10]]
        check("each of the ten identical listeners is pushed", all(p is not None for p in pushes),
              f"{sum(p is None for p in pushes)} got nothing")
        check("each push carries its own connection's listenId",
              all(p is not None and p.get("listenId") == r.get("listenId")
                  for p, r in zip(pushes, registered[:10])), "")
        check("the identical listeners all see the same hash",
              len({p.get("resultHash") for p in pushes if p is not None}) == 1, "")
        check("the listener with a different query is not pushed", listeners[10].recv(timeout=1.5) is None)
        save_doc(writer, {"_id": "shared-1", "kind": "shared", "v": 2})
        again = [conn.recv(timeout=5.0) for conn in listeners[:10]]
        check("a second change pushes every identical listener again", all(p is not None for p in again),
              f"{sum(p is None for p in again)} got nothing")
    finally:
        for conn in listeners:
            conn.close()
        delete_doc(writer, "shared-1")


def test_revoking_one_user_ends_only_their_listen_in_a_shared_query(writer: Conn):
    section("LISTEN: revoking one reader ends only that reader's listen on a query others share")
    reader_name = "listen_shared_reader"
    writer.send({"type": "DELETE_USER", "username": reader_name})
    check_status("create a reader on the listen database", writer.send({
        "type": "CREATE_USER", "username": reader_name, "password": "listen_reader1234", "admin": False,
        "globalPermissions": [], "databasePermissions": {DB: "READ"}, "collectionPermissions": {}}), "OK")
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "revshare"}}]
    try:
        with Conn() as reader, Conn.authenticated() as admin_a, Conn.authenticated() as admin_b:
            check_status("AUTHENTICATE as the reader", reader.authenticate(reader_name, "listen_reader1234"), "OK")
            reader_id = listen(reader, steps).get("listenId")
            admin_ids = [listen(admin_a, steps).get("listenId"), listen(admin_b, steps).get("listenId")]
            check_status("revoke the reader's database permission", writer.send({
                "type": "CHANGE_PERMISSIONS", "username": reader_name, "admin": False, "globalPermissions": [],
                "databasePermissions": {}, "collectionPermissions": {}}), "OK")
            save_doc(writer, {"_id": "revshare-1", "kind": "revshare"})
            check_listen_ended("the revoked reader is told its listen ended", reader.recv(timeout=5.0), reader_id)
            for conn, listen_id in zip((admin_a, admin_b), admin_ids):
                pushed = conn.recv(timeout=5.0)
                check("an admin sharing the query is still pushed",
                      pushed is not None and pushed.get("listenId") == listen_id and pushed.get("status") == "OK",
                      str(pushed)[:200])
    finally:
        delete_doc(writer, "revshare-1")
        writer.send({"type": "DELETE_USER", "username": reader_name})


SORTED_COLL = "listen_sorted"
SORTED_SEED = 300
SORTED_WRITERS = 3
SORTED_WRITES_PER_WRITER = 120
SORTED_STEPS = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "sorted"}},
                {"type": "SORT", "fieldName": "score", "ascending": False},
                {"type": "LIMIT", "limit": 5}]


def sorted_signature(results) -> list:
    return [(d.get("_id"), d.get("score")) for d in (results or [])]


def pushed_frames(conn: Conn, buffered: bytearray, timeout: float) -> list:
    ready, _, _ = select.select([conn.s], [], [], timeout)
    if ready:
        buffered.extend(conn.s.recv(1 << 20))
    frames = []
    while b"\n" in buffered:
        line, _, rest = bytes(buffered).partition(b"\n")
        buffered[:] = rest
        if line.strip():
            frames.append(json.loads(line))
    return frames


def test_sorted_listen_under_concurrent_writes_ends_with_final_state(writer: Conn, listener: Conn):
    section("LISTEN: an index-sorted top 5 re-run under concurrent writers ends on the final state")
    writer.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": SORTED_COLL})
    writer.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": SORTED_COLL,
                 "objects": [{"_id": f"s{i:04d}", "kind": "sorted", "score": i} for i in range(SORTED_SEED)]})
    check_status("score indexed", writer.send({"type": "CREATE_INDEX", "databaseName": DB,
                                               "collectionName": SORTED_COLL, "fieldName": "score"}), "OK")
    started = listen(listener, SORTED_STEPS, coll=SORTED_COLL)
    check_status("sorted LISTEN registered", started, "OK")
    listen_id = started.get("listenId")
    last = [started.get("results")]

    def write(seed: int):
        rng = random.Random(seed)
        with Conn() as conn:
            conn.authenticate()
            for _ in range(SORTED_WRITES_PER_WRITER):
                conn.send({"type": "SAVE", "databaseName": DB, "collectionName": SORTED_COLL,
                           "object": {"_id": f"s{rng.randrange(SORTED_SEED + 50):04d}", "kind": "sorted",
                                      "score": rng.randrange(10_000)}})

    writers = [threading.Thread(target=write, args=(seed,)) for seed in range(SORTED_WRITERS)]
    for thread in writers:
        thread.start()
    pushes = 0
    buffered = bytearray()
    quiet_since = None
    while quiet_since is None or time.time() - quiet_since < 3.0:
        frames = pushed_frames(listener, buffered, 0.5)
        for frame in frames:
            if frame.get("results") is not None:
                last[0] = frame.get("results")
                pushes += 1
        writing = any(thread.is_alive() for thread in writers)
        if writing or frames:
            quiet_since = None
        elif quiet_since is None:
            quiet_since = time.time()
    for thread in writers:
        thread.join(60)
    final = writer.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": SORTED_COLL,
                         "aggregationSteps": SORTED_STEPS})
    check(f"the listener was notified while the writers ran ({pushes} pushes)", pushes > 0)
    check("the last pushed top 5 equals the final state",
          sorted_signature(last[0]) == sorted_signature(final.get("results")),
          f"last={sorted_signature(last[0])} final={sorted_signature(final.get('results'))}")
    stop_listen(listener, listen_id)
    writer.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": SORTED_COLL})


def main():
    bu.banner("Listenable queries (LISTEN / STOP_LISTEN) test suite", HOST, PORT)

    with Conn() as admin_conn:
        admin_conn.authenticate()
        setup(admin_conn)

        try:
            test_validation(admin_conn)
            test_initial_response(admin_conn)
            test_no_push_on_unrelated_write(admin_conn)
            test_aggregate_vector_nearest(admin_conn)
            test_a_listen_whose_map_exceeds_the_entry_size_answers_400_28(admin_conn)
            test_oversized_listen_is_400_37(admin_conn)

            with Conn() as writer_conn, Conn() as listener_conn:
                writer_conn.authenticate()
                listener_conn.authenticate()

                test_push_on_matching_insert(writer_conn, listener_conn)
                test_no_push_on_non_matching_insert(writer_conn, listener_conn)
                test_push_on_delete(writer_conn, listener_conn)
                test_push_on_geo_distance_match(writer_conn, listener_conn)
                test_no_push_on_geo_distance_non_match(writer_conn, listener_conn)
                test_push_on_geo_within_match(writer_conn, listener_conn)
                test_push_on_vector_nearest_closer(writer_conn, listener_conn)
                test_no_push_on_vector_farther(writer_conn, listener_conn)
                test_push_on_reorder_inside_a_sorted_top_k(writer_conn, listener_conn)
                test_push_on_reorder_inside_an_unsorted_nearest_top_k(writer_conn, listener_conn)
                test_push_on_reorder_after_a_sort_then_distinct(writer_conn, listener_conn)
                test_no_push_when_an_unordered_group_by_result_is_unchanged(writer_conn, listener_conn)
                test_stop_listen(writer_conn, listener_conn)
                test_stop_listen_from_another_client_is_refused(writer_conn, listener_conn)
                test_multiple_listeners(writer_conn, listener_conn)
                test_a_pushed_frame_parses_with_its_own_listen_id_and_a_64_hex_hash(writer_conn, listener_conn)
                test_identical_listeners_on_many_connections_each_get_their_own_push(writer_conn)
                test_revoking_one_user_ends_only_their_listen_in_a_shared_query(writer_conn)
                test_transactional_commit_pushes_once_with_the_final_state(writer_conn, listener_conn)
                test_listener_survives_a_concurrent_bulk_save(writer_conn, listener_conn)
                test_sorted_listen_under_concurrent_writes_ends_with_final_state(writer_conn, listener_conn)
                test_a_revoked_reader_is_told_its_listen_ended(writer_conn)
                test_dropping_the_collection_ends_the_listen(writer_conn, listener_conn)
                test_dropping_a_joined_collection_ends_the_listen(writer_conn, listener_conn)
                test_disconnect_cleanup(writer_conn)
                test_a_non_reading_listener_is_disconnected_and_others_still_notified(writer_conn)

            test_a_connection_cannot_register_past_its_listen_limit()
            test_listens_per_user_cap_across_connections_is_400_38()
            test_retained_budget_released_on_disconnect()
            test_unauthenticated_listen()

        finally:
            teardown(admin_conn)

    bu.summary()


if __name__ == "__main__":
    main()
