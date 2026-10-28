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
PASSWORD = "Tst-Admin-9xQ2mV"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR_PATH = os.path.join(REPO_ROOT, "target", "lwnrdb.jar")
SERVER_BIN = os.environ.get("LWNRDB_SERVER_BIN")
SERVER_PROC_PATTERN = SERVER_BIN or JAR_PATH


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


def summary(on_failure: Optional[Callable] = None, suffix: str = "") -> None:
    print("\n" + "═" * WIDTH)
    if _failures == 0:
        print(f"  {GREEN}All checks passed.{RESET}{suffix}")
    else:
        print(f"  {RED}{_failures} check(s) FAILED.{RESET}{suffix}")
        if on_failure is not None:
            on_failure()
    print("═" * WIDTH + "\n")

    sys.exit(0 if _failures == 0 else 1)


def non_reading_conn(receive_buffer: int = 8192) -> "Conn":
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, receive_buffer)
    sock.settimeout(60.0)
    sock.connect((HOST, PORT))
    conn = Conn(sock=sock)
    conn.authenticate()
    return conn


def send_without_reading(conn: "Conn", payload: dict) -> bool:
    try:
        conn.s.sendall((json.dumps(payload) + "\n").encode())
        return True
    except OSError:
        return False


def read_until_closed(conn: "Conn", timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ready, _, _ = select.select([conn.s], [], [], max(deadline - time.time(), 0))
        if not ready:
            return False
        try:
            if not conn.s.recv(1 << 20):
                return True
        except OSError:
            return True
    return False


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
        return self.send_bytes(line.encode(), timeout, strict)

    def send_bytes(self, line: bytes, timeout: Optional[float] = None, strict: bool = False) -> dict:
        if timeout is not None:
            self.s.settimeout(timeout)
        try:
            self.s.sendall(line + b"\n")
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
        ready, _, _ = select.select([self.s], [], [], timeout)
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
    if SERVER_BIN:
        if not os.path.isfile(SERVER_BIN):
            print(f"\n[ERROR] Native binary not found at {SERVER_BIN} (LWNRDB_SERVER_BIN). "
                  f"Build it first: mvn -Pnative package -DskipTests\n")
            sys.exit(1)
        return
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)


def _config_value(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def write_config(work_dir: str, port: int, *, admin_password: Optional[str] = None, **settings) -> None:
    password = PASSWORD if admin_password is None else admin_password
    lines = [f"port={port}", "filePath=db", "logPath=logs",
             f"defaultAdminUsername={USERNAME}", f"defaultAdminPassword={password}"]
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


def open_intents(folder: str) -> list:
    if not os.path.isdir(folder):
        return []
    found = []
    for name in sorted(n for n in os.listdir(folder) if n.endswith(".intents")):
        with open(os.path.join(folder, name), "rb") as fp:
            lines = fp.read().decode("utf-8", errors="replace").split("\n")
        begun, done = [], set()
        for line in lines:
            fields = line.split(PK_FIELD_SEPARATOR)
            if fields[0] == "INTENT" and len(fields) > 1:
                begun.append(fields[1])
            elif fields[0] == "DONE" and len(fields) == 2:
                done.add(fields[1])
        found += [f"{name}#{seq}" for seq in begun if seq not in done]
    return found


WAL_HEADER_BYTES = 18


def damage_after_the_last_checkpoint(folder: str):
    for name in (n for n in os.listdir(folder) if n.endswith(".wal")):
        os.remove(os.path.join(folder, name))


def logged_writes(folder: str) -> int:
    if not os.path.isdir(folder):
        return 0
    logged = 0
    for name in (n for n in os.listdir(folder) if n.endswith(".wal")):
        with open(os.path.join(folder, name), "rb") as fp:
            data = fp.read()
        at = 0
        while at + WAL_HEADER_BYTES <= len(data) and data[at:at + 1] == b"W":
            kind = data[at + 1:at + 2]
            length = int.from_bytes(data[at + 10:at + 14], "big")
            if at + WAL_HEADER_BYTES + length > len(data):
                break
            logged = 0 if kind == b"C" else logged + (1 if kind == b"R" else -1)
            at += WAL_HEADER_BYTES + length
    return logged


def read_pk_log(pk_path: str) -> list:
    with open(pk_path, "rb") as fp:
        lines = fp.read().decode("utf-8", errors="replace").split("\n")
    return [line.rstrip("\r").split(PK_FIELD_SEPARATOR) for line in lines[:-1]]


def read_pk_rows(pk_path: str) -> list:
    folded = {}
    for fields in read_pk_log(pk_path):
        if len(fields) == 2 and fields[1] == "-":
            folded.pop(fields[0], None)
        elif len(fields) == 5:
            folded[fields[0]] = [fields[0]] + [int(value) for value in fields[1:]]
    return [folded[doc_id] for doc_id in sorted(folded)]


def write_pk_rows(pk_path: str, rows: list) -> None:
    with open(pk_path, "w", encoding="utf-8", newline="") as fp:
        for row in sorted(rows, key=lambda r: r[0]):
            fp.write(PK_FIELD_SEPARATOR.join(str(value) for value in row) + "\n")


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


def client_argv() -> list:
    if SERVER_BIN:
        return [SERVER_BIN]
    return ["java", "-jar", JAR_PATH]


def server_argv(xmx: str = "512m", jar: Optional[str] = None) -> list:
    if SERVER_BIN:
        return [SERVER_BIN, f"-Xmx{xmx}"]
    return ["java", f"-Xmx{xmx}", "-jar", jar or JAR_PATH]


def start_server(work_dir: str, log_path: str, jar: Optional[str] = None,
                 xmx: str = "512m", host: Optional[str] = None,
                 port: Optional[int] = None, settle: float = 0.5):
    log = open(log_path, "ab")
    proc = subprocess.Popen(server_argv(xmx, jar), stdout=log, stderr=log, cwd=work_dir)
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


def run_until_exit(work_dir: str, log_path: str, jar: Optional[str] = None, xmx: str = "512m",
                   timeout_s: float = 30.0) -> Optional[int]:
    with open(log_path, "ab") as log:
        proc = subprocess.Popen(server_argv(xmx, jar), stdout=log, stderr=log, cwd=work_dir)
    try:
        return proc.wait(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        return None


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
