import os
import re
import socket
import tempfile
import urllib.error
import urllib.request

import base_utils as bu
from base_utils import Conn, check, check_status, section

METRICS_PORT = int(os.environ.get("METRICS_TEST_PORT", "9460"))
TOKEN = "metrics-T0ken"
DB = "metrics_db"
COLL = "items"
SAVES = 5
CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})? (\S+)$')
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')


def _request(path: str = "/metrics", token=TOKEN, method: str = "GET"):
    request = urllib.request.Request(f"http://127.0.0.1:{METRICS_PORT}{path}", method=method)
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.headers.get("Content-Type"), response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.headers.get("Content-Type"), error.read().decode()


def _parse(text: str) -> dict:
    samples = {}
    families = set()
    for line in text.splitlines():
        if line.startswith("# TYPE "):
            families.add(line.split(" ")[2])
            continue
        if line.startswith("#") or not line:
            continue
        match = SAMPLE.match(line)
        if match is None:
            raise ValueError(f"not an exposition line: {line}")
        name = match.group(1)
        if not any(name == family or name.startswith(family + "_") for family in families):
            raise ValueError(f"{name} has no TYPE line before it")
        labels = tuple(sorted(LABEL.findall(match.group(3) or "")))
        samples[(name, labels)] = float(match.group(4))
    return samples


def scrape() -> dict:
    status, _, body = _request()
    if status != 200:
        raise RuntimeError(f"scrape answered {status}: {body[:200]}")
    return _parse(body)


def value(samples: dict, name: str, **labels) -> float:
    return samples.get((f"lwnrdb_{name}", tuple(sorted(labels.items()))), 0.0)


def requests(samples: dict, operation: str, status: str, code: str = "") -> float:
    return value(samples, "requests_total", operation=operation, status=status, code=code)


def test_the_endpoint_is_off_by_default():
    section("The metrics endpoint is off unless metricsPort is set")
    check("Nothing listens on the metrics port of a server started without metricsPort",
          not bu.port_open("127.0.0.1", METRICS_PORT))


def test_a_scrape_is_prometheus_text():
    section("A scrape answers 200 with the Prometheus text format")
    status, content_type, body = _request()
    check("GET /metrics with the token answers 200", status == 200, f"status={status} body={body[:200]}")
    check("The content type is the Prometheus text format 0.0.4", content_type == CONTENT_TYPE, str(content_type))
    try:
        samples = _parse(body)
        check("Every line parses and every sample follows its family's TYPE line", len(samples) > 0)
    except ValueError as error:
        check("Every line parses and every sample follows its family's TYPE line", False, str(error))


def test_build_info_matches_server_info():
    section("lwnrdb_build_info names what SERVER_INFO names")
    with Conn.authenticated() as c:
        info = c.send({"type": "SERVER_INFO"}).get("server") or {}
    labels = {"version": info.get("version"), "protocol_version": str(info.get("protocolVersion")),
              "storage_format_version": str(info.get("storageFormatVersion"))}
    check("build_info carries the release and both format versions", value(scrape(), "build_info", **labels) == 1,
          f"server_info={info}")


def test_requests_are_counted_by_operation_status_and_code():
    section("Requests are counted by operation, status and error code")
    before = scrape()
    with Conn.authenticated() as c:
        for i in range(SAVES):
            check_status(f"save {i}", c.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                                              "object": {"_id": f"m{i}", "n": i}}), "OK")
        missing = c.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "absent"})
    with Conn() as anonymous:
        refused = anonymous.send({"type": "LIST_DATABASES"})
    after = scrape()
    check(f"SAVE/OK grew by {SAVES}", requests(after, "SAVE", "OK") - requests(before, "SAVE", "OK") == SAVES)
    missing_code = missing.get("errorCode", "")
    check("The missing FIND_BY_ID is counted under its status and code",
          requests(after, "FIND_BY_ID", missing.get("status"), missing_code)
          - requests(before, "FIND_BY_ID", missing.get("status"), missing_code) == 1, str(missing))
    check("The unauthenticated LIST_DATABASES is counted under 401-1",
          requests(after, "LIST_DATABASES", "UNAUTHENTICATED", "401-1")
          - requests(before, "LIST_DATABASES", "UNAUTHENTICATED", "401-1") == 1, str(refused))
    durations = value(after, "request_duration_seconds_count", operation="SAVE") - value(
        before, "request_duration_seconds_count", operation="SAVE")
    check(f"The SAVE latency histogram counted {SAVES} more", durations == SAVES, f"delta={durations}")
    check("fsync_total grew across the synced saves", value(after, "fsync_total") > value(before, "fsync_total"))


def test_the_connection_gauge_follows_open_connections():
    section("lwnrdb_connections follows the open client connections")
    baseline = value(scrape(), "connections")
    opened = [Conn.authenticated(), Conn.authenticated()]
    try:
        check("Two more connections show", bu.wait_until(lambda: value(scrape(), "connections") == baseline + 2, 10),
              f"baseline={baseline} now={value(scrape(), 'connections')}")
    finally:
        for conn in opened:
            conn.close()
    check("They leave the gauge once closed", bu.wait_until(lambda: value(scrape(), "connections") == baseline, 10),
          f"baseline={baseline} now={value(scrape(), 'connections')}")


def test_the_document_total_matches_database_stats():
    section("lwnrdb_documents agrees with GET_DATABASE_STATS")

    def stats_entries():
        with Conn.authenticated() as c:
            return ((c.send({"type": "GET_DATABASE_STATS"}).get("stats") or {}).get("totals") or {}).get("entryCount")

    agreed = bu.wait_until(lambda: stats_entries() == SAVES and value(scrape(), "documents") == SAVES, 15)
    check(f"Both report {SAVES} documents", agreed,
          f"stats={stats_entries()} metrics={value(scrape(), 'documents')}")


def test_refusals():
    section("A scrape without the token, on another path or with another method is refused")
    check("No token answers 401", _request(token=None)[0] == 401)
    check("A wrong token answers 401", _request(token="wrong")[0] == 401)
    check("/ answers 404", _request(path="/")[0] == 404)
    check("POST /metrics answers 405", _request(method="POST")[0] == 405)
    with socket.create_connection(("127.0.0.1", METRICS_PORT), timeout=10) as raw:
        raw.sendall(b"GET /metrics HTTP/1.1\r\nX-Pad: " + b"a" * 9000 + b"\r\n\r\n")
        head = raw.recv(64)
    check("A head over 8 KB answers 431", head.startswith(b"HTTP/1.1 431"), repr(head))


def test_a_metrics_port_equal_to_the_client_port_refuses_to_start(write_config, port: int):
    section("A metricsPort equal to port refuses to start")
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-metrics-collision-")
    write_config(work_dir, port, metricsPort=port)
    log_path = os.path.join(work_dir, "server.out")
    code = bu.run_until_exit(work_dir, log_path)
    log = bu.read_log(log_path)
    check("The server exits non-zero", code is not None and code != 0, f"exit={code}")
    check("A FATAL line names metricsPort", "FATAL" in log and "metricsPort" in log, log[-600:])


def run_all(write_config, port: int):
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-metrics-")
    write_config(work_dir, port, metricsPort=METRICS_PORT, metricsToken=TOKEN)
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Metrics phase working dir: {work_dir}")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=port)
        with Conn.authenticated() as c:
            check_status("create the database", c.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
            check_status("create the collection", c.send({"type": "CREATE_COLLECTION", "databaseName": DB,
                                                          "collectionName": COLL}), "OK")
        test_a_scrape_is_prometheus_text()
        test_build_info_matches_server_info()
        test_requests_are_counted_by_operation_status_and_code()
        test_the_connection_gauge_follows_open_connections()
        test_the_document_total_matches_database_stats()
        test_refusals()
    finally:
        bu.stop_server(proc, port=port, wait_for_port=True)
    test_a_metrics_port_equal_to_the_client_port_refuses_to_start(write_config, port)
    return log_path
