import hashlib
import json
import os
import tempfile

import base_utils as bu
from base_utils import Conn, check, check_status, section

DB = "format_db"
COLL = "docs"
DOC = {"_id": "kept", "body": "written before the refusals"}


def _stamp_path(work_dir: str) -> str:
    return os.path.join(work_dir, "db", "format.json")


def _read_stamp(work_dir: str) -> dict:
    with open(_stamp_path(work_dir), "rb") as fp:
        return json.loads(fp.read())


def _write_stamp(work_dir: str, content: bytes):
    with open(_stamp_path(work_dir), "wb") as fp:
        fp.write(content)


def _folder_digest(work_dir: str) -> dict:
    digest = {}
    root = os.path.join(work_dir, "db")
    for folder, _, files in os.walk(root):
        for name in files:
            path = os.path.join(folder, name)
            with open(path, "rb") as fp:
                digest[os.path.relpath(path, root)] = hashlib.sha256(fp.read()).hexdigest()
    return digest


def _refused(label: str, work_dir: str, port: int, expected: list):
    log_path = os.path.join(work_dir, f"refused-{label.replace(' ', '-')}.out")
    before = _folder_digest(work_dir)
    code = bu.run_until_exit(work_dir, log_path)
    log = bu.read_log(log_path)
    fatal = next((line for line in log.splitlines() if "FATAL" in line), log[-600:])
    check(f"{label}: the server exits non-zero", code is not None and code != 0, f"exit={code}")
    check(f"{label}: a FATAL line says why", "FATAL" in log and "will not start" in log
          and all(text in fatal for text in expected), fatal)
    check(f"{label}: no port was opened", not bu.port_open(port=port))
    check(f"{label}: every file of the folder is left as it was", _folder_digest(work_dir) == before)


def test_a_fresh_folder_is_stamped(work_dir: str, port: int) -> bytes:
    section("A fresh folder is stamped with the storage format")
    proc = bu.start_server(work_dir, os.path.join(work_dir, "server.out"), port=port)
    try:
        with Conn.authenticated(port=port) as c:
            check_status("create the database", c.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
            check_status("create the collection", c.send({"type": "CREATE_COLLECTION", "databaseName": DB,
                                                          "collectionName": COLL}), "OK")
            check_status("save a document", c.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                                                    "object": DOC}), "OK")
            info = c.send({"type": "SERVER_INFO"}).get("server") or {}
    finally:
        bu.stop_server(proc, port=port)
    check("db/format.json exists", os.path.isfile(_stamp_path(work_dir)))
    stamp = _read_stamp(work_dir)
    check("its formatVersion is SERVER_INFO's storageFormatVersion",
          isinstance(stamp.get("formatVersion"), int) and stamp["formatVersion"] == info.get("storageFormatVersion"),
          f"stamp={stamp}, server={info}")
    check("it names the release that wrote it", stamp.get("writtenBy") == info.get("version"),
          f"stamp={stamp}, server={info}")
    with open(_stamp_path(work_dir), "rb") as fp:
        return fp.read()


def test_a_newer_format_refuses_to_start(work_dir: str, port: int, original: dict):
    section("A folder stamped by a newer storage format refuses to start")
    newer = {"formatVersion": original["formatVersion"] + 1, "writtenBy": "9.9.9"}
    _write_stamp(work_dir, json.dumps(newer).encode())
    _refused("newer format", work_dir, port, ["storage format", "9.9.9", "format.json"])


def test_an_invalid_format_version_refuses_to_start(work_dir: str, port: int):
    section("A stamp whose formatVersion is not a positive whole number refuses to start")
    _write_stamp(work_dir, b'{"formatVersion":0,"writtenBy":"0.0.1"}')
    _refused("format version 0", work_dir, port, ["format.json", "must be a positive whole number"])


def test_a_malformed_stamp_refuses_to_start(work_dir: str, port: int):
    section("A malformed stamp refuses to start instead of being read as a fresh folder")
    _write_stamp(work_dir, b"not json")
    _refused("malformed stamp", work_dir, port, ["format.json", "not a valid storage format stamp"])


def test_the_original_stamp_starts_and_serves_the_data(work_dir: str, port: int, original: bytes):
    section("With the original stamp back, the server starts and serves what it held")
    _write_stamp(work_dir, original)
    proc = bu.start_server(work_dir, os.path.join(work_dir, "server.out"), port=port)
    try:
        with Conn.authenticated(port=port) as c:
            found = c.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": DOC["_id"]})
    finally:
        bu.stop_server(proc, port=port)
    check("the document written before the refusals is there", (found.get("object") or {}).get("body") == DOC["body"],
          json.dumps(found))
    with open(_stamp_path(work_dir), "rb") as fp:
        check("a matching stamp is never rewritten", fp.read() == original)


def run_all(write_config, port: int):
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-client-format-")
    write_config(work_dir, port)
    original = test_a_fresh_folder_is_stamped(work_dir, port)
    test_a_newer_format_refuses_to_start(work_dir, port, json.loads(original))
    test_an_invalid_format_version_refuses_to_start(work_dir, port)
    test_a_malformed_stamp_refuses_to_start(work_dir, port)
    test_the_original_stamp_starts_and_serves_the_data(work_dir, port, original)
