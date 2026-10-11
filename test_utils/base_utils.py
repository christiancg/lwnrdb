import json
import os
import select
import socket
import subprocess
import sys
import time
from typing import Callable, Optional


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

WIDTH = 70

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
RESET = "\033[0m"

PASS = f"{GREEN}PASS{RESET}"
FAIL = f"{RED}FAIL{RESET}"
WARN = f"{YELLOW}WARN{RESET}"


HOST = "127.0.0.1"
PORT = 8989
USERNAME = "admin"
PASSWORD = "administrator"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR_PATH = os.path.join(REPO_ROOT, "target", "lwnrdb-1.0-SNAPSHOT.jar")


def configure(host: Optional[str] = None, port: Optional[int] = None,
              username: Optional[str] = None, password: Optional[str] = None) -> None:
    global HOST, PORT, USERNAME, PASSWORD
    if host is not None:
        HOST = host
    if port is not None:
        PORT = port
    if username is not None:
        USERNAME = username
    if password is not None:
        PASSWORD = password


_failures = 0
_warnings = 0


def failure_count() -> int:
    return _failures


def warning_count() -> int:
    return _warnings


def record_failure() -> None:
    global _failures
    _failures += 1


def check(label: str, ok: bool, detail: str = "") -> bool:
    global _failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        _failures += 1
    return ok


def warn(label: str, detail: str = "") -> None:
    global _warnings
    print(f"  [{WARN}] {label}")
    if detail:
        print(f"         {detail}")
    _warnings += 1


def check_status(label: str, response: dict, expected_status: str) -> bool:
    actual = response.get("status")
    return check(label, actual == expected_status,
                 f"expected={expected_status}  got={actual}  "
                 f"code={response.get('errorCode')}  msg={response.get('message', '')!r}")


def check_code(label: str, response: dict, expected_status: str, expected_code: str) -> bool:
    actual_status = response.get("status")
    actual_code = response.get("errorCode")
    ok = actual_status == expected_status and actual_code == expected_code
    return check(label, ok,
                 f"expected={expected_status}/{expected_code}  "
                 f"got={actual_status}/{actual_code}  msg={response.get('message', '')!r}")


def check_result(label: str, response: dict, expected) -> bool:
    ok = response.get("status") == "OK" and response.get("result") == expected
    return check(label, ok,
                 f"expected result={expected!r} got={response.get('result')!r} "
                 f"status={response.get('status')} msg={response.get('message')!r}")


def dig(response: dict, path: str):
    cur = response
    for part in path.split("."):
        if cur is None:
            return None
        if isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def check_field(label: str, response: dict, path: str, expected) -> bool:
    actual = dig(response, path)
    return check(label, actual == expected,
                 f"path={path}  expected={expected!r}  got={actual!r}")


def banner(title: str, host: Optional[str] = None, port: Optional[int] = None) -> None:
    print("\n" + "═" * WIDTH)
    print(f"  LWNRDB — {title}")
    print("═" * WIDTH)
    if host is not None:
        print(f"  Connecting to {host}:{port}")


def section(title: str) -> None:
    print(f"\n{'─' * WIDTH}")
    print(f"  {title}")
    print(f"{'─' * WIDTH}")


def summary(on_failure: Optional[Callable[[], None]] = None, suffix: str = "") -> None:
    print("\n" + "═" * WIDTH)
    if _failures == 0:
        print(f"  {GREEN}All checks passed.{RESET}{suffix}")
    else:
        print(f"  {RED}{_failures} check(s) FAILED.{RESET}{suffix}")
        if on_failure is not None:
            on_failure()
    print("═" * WIDTH + "\n")

    sys.exit(0 if _failures == 0 else 1)


UNPARSED = "unparsed"


def _refuse_non_rfc_constant(token: str):
    raise ValueError(f"the server answered with the non-RFC JSON constant {token}")


class Conn:

    def __init__(self, host: Optional[str] = None, port: Optional[int] = None,
                 timeout: float = 60.0, sock=None):
        if sock is None:
            sock = socket.create_connection((host or HOST, port or PORT), timeout=timeout)
        else:
            sock.settimeout(timeout)
        self.s = sock
        self.f = self.s.makefile("rb")

    def send(self, payload: dict, timeout: Optional[float] = None, strict: bool = False) -> dict:
        return self.send_raw(json.dumps(payload), timeout, strict)

    def send_raw(self, line: str, timeout: Optional[float] = None, strict: bool = False) -> dict:
        if timeout is not None:
            self.s.settimeout(timeout)
        try:
            self.s.sendall((line + "\n").encode())
        except (BrokenPipeError, OSError):
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        return self._read_response(strict)

    def _read_response(self, strict: bool = False) -> dict:
        try:
            raw = self.f.readline().decode().strip()
        except (OSError, ConnectionError):
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        if not raw:
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        try:
            if strict:
                return json.loads(raw, parse_constant=_refuse_non_rfc_constant)
            return json.loads(raw)
        except json.JSONDecodeError:
            unparsed = {"status": "ERROR", "message": raw}
            if strict:
                unparsed[UNPARSED] = True
            return unparsed

    def recv(self, timeout: float = 2.0) -> Optional[dict]:
        ready, _, _ = select.select([self.f.raw._sock], [], [], timeout)
        if not ready:
            return None
        try:
            raw = self.f.readline().decode().strip()
        except OSError:
            return None
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return None

    @classmethod
    def authenticated(cls, username: Optional[str] = None, password: Optional[str] = None,
                      **conn_kwargs) -> "Conn":
        conn = cls(**conn_kwargs)
        conn.authenticate(username, password)
        return conn

    def authenticate(self, username: Optional[str] = None,
                     password: Optional[str] = None) -> dict:
        return self.send({"type": "AUTHENTICATE",
                          "username": USERNAME if username is None else username,
                          "password": PASSWORD if password is None else password})

    def close(self) -> None:
        for closeable in (self.f, self.s):
            try:
                closeable.close()
            except OSError:
                pass

    def __enter__(self) -> "Conn":
        return self

    def __exit__(self, *_) -> None:
        self.close()


def port_open(host: Optional[str] = None, port: Optional[int] = None) -> bool:
    try:
        with socket.create_connection((host or HOST, port or PORT), timeout=0.5):
            return True
    except OSError:
        return False


def ids_of(response: dict) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


def count_of(response: dict):
    return ((response.get("results") or [{}])[0]).get("count")


def filter_step(field: str, operator: str, value) -> dict:
    return {"type": "FILTER", "operator": {"fieldOperatorType": operator, "field": field, "value": value}}


def _attempt(predicate: Callable[[], object]):
    try:
        return predicate()
    except (OSError, RuntimeError):
        return False


def wait_until(predicate: Callable[[], object], timeout_s: float, interval_s: float = 0.4):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        result = _attempt(predicate)
        if result:
            return result
        time.sleep(interval_s)
    return _attempt(predicate)


def holds_throughout(predicate: Callable[[], object], seconds: float, interval_s: float = 0.5) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if not _attempt(predicate):
            return False
        time.sleep(interval_s)
    return True


def require_jar(jar: str = JAR_PATH) -> None:
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)


def _config_value(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def write_config(work_dir: str, port: int, **settings) -> None:
    lines = [f"port={port}", "filePath=db", "logPath=logs",
             f"defaultAdminUsername={USERNAME}", f"defaultAdminPassword={PASSWORD}"]
    lines += [f"{key}={_config_value(value)}" for key, value in settings.items()]
    with open(os.path.join(work_dir, "lwnrdb.cfg"), "w") as fp:
        fp.write("\n".join(lines) + "\n")


PK_FIELD_SEPARATOR = "\x1f"
INDEX_ESCAPES = {"\\": "\\", "n": "\n", "r": "\r", "s": "\x1f"}


def unescape_index_token(token: str) -> str:
    if "\\" not in token:
        return token
    out = []
    i = 0
    while i < len(token):
        if token[i] != "\\" or i + 1 >= len(token):
            out.append(token[i])
            i += 1
            continue
        out.append(INDEX_ESCAPES.get(token[i + 1], "\\" + token[i + 1]))
        i += 2
    return "".join(out)


def local_commit_markers(work_dir: str) -> int:
    folder = os.path.join(work_dir, "db", "admin", "transactions")
    if not os.path.isdir(folder):
        return 0
    total = 0
    for name in sorted(os.listdir(folder)):
        if name.endswith(".dat"):
            with open(os.path.join(folder, name), "r", encoding="utf-8", errors="replace") as fp:
                total += sum(1 for line in fp if "|localcommit" in line)
    return total


def read_pk_rows(pk_path: str) -> list:
    with open(pk_path, "r", encoding="utf-8") as fp:
        return [[fields[0]] + [int(value) for value in fields[1:]]
                for fields in (line.rstrip("\r\n").split(PK_FIELD_SEPARATOR) for line in fp) if len(fields) == 5]


def write_pk_rows(pk_path: str, rows: list) -> None:
    with open(pk_path, "w", encoding="utf-8", newline="") as fp:
        for row in sorted(rows, key=lambda r: r[0]):
            fp.write(PK_FIELD_SEPARATOR.join(str(value) for value in row) + "\n")


def write_compaction_marker(collection_folder: str, kind: str, db: str, coll: str, row: list, page_bytes: bytes,
                            target_page: int = -1, target_length_before: int = 0) -> None:
    doc_id, position, length, page, version = row
    header = PK_FIELD_SEPARATOR.join(str(value) for value in (
        kind, db, coll, page, position, len(page_bytes), length, doc_id, version, target_page, target_length_before))
    with open(os.path.join(collection_folder, f"{coll}-{page}.compacting"), "wb") as fp:
        fp.write(header.encode("utf-8") + b"\n" + page_bytes[position:])


def read_log(log_path: str) -> str:
    try:
        with open(log_path, "rb") as fp:
            return fp.read().decode(errors="replace")
    except OSError:
        return ""


def dump_log(log_path: str, tail_bytes: int = 4000) -> None:
    try:
        with open(log_path, "rb") as fp:
            tail = fp.read()[-tail_bytes:].decode(errors="replace")
        print(f"--- server log tail ---\n{tail}\n--- end ---", file=sys.stderr)
    except OSError:
        pass


def start_server(work_dir: str, log_path: str, jar: Optional[str] = None,
                 xmx: str = "512m", host: Optional[str] = None,
                 port: Optional[int] = None, settle: float = 0.5):
    jar = jar or JAR_PATH
    log = open(log_path, "ab")
    proc = subprocess.Popen(["java", f"-Xmx{xmx}", "-jar", jar],
                            stdout=log, stderr=log, cwd=work_dir)
    deadline = time.time() + 60.0
    while time.time() < deadline:
        if port_open(host, port):
            time.sleep(settle)
            return proc
        if proc.poll() is not None:
            break
        time.sleep(0.2)
    dump_log(log_path)
    proc.kill()
    raise RuntimeError("server did not come up in time")


def stop_server(proc, host: Optional[str] = None, port: Optional[int] = None,
                wait_for_port: bool = True) -> None:
    if proc is None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
    if wait_for_port:
        deadline = time.time() + 30.0
        while time.time() < deadline and port_open(host, port):
            time.sleep(0.2)
