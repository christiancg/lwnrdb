"""Shared plumbing for the LWNRDB integration suites.

Every `test_*.py` suite in this folder speaks the same wire protocol, prints the same
report format and gates CI on the same exit code, so all of that lives here rather than
being copy-pasted eighteen times.

A suite typically looks like:

    import base_utils as bu
    from base_utils import Conn, check, check_status, section

    bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

    def main():
        bu.banner("My feature test suite", HOST, PORT)
        section("Setup")
        with Conn() as c:
            check_status("create database", c.send({...}), "OK")
        bu.summary()

Stdlib only: CI has no `pip install` step for these suites.
"""

import json
import os
import select
import socket
import subprocess
import sys
import time
from typing import Callable, Optional

# ── report format ────────────────────────────────────────────────────────────

WIDTH = 70

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
RESET = "\033[0m"

PASS = f"{GREEN}PASS{RESET}"
FAIL = f"{RED}FAIL{RESET}"
WARN = f"{YELLOW}WARN{RESET}"

# ── defaults, set once per suite via configure() ─────────────────────────────

HOST = "127.0.0.1"
PORT = 8989
USERNAME = "admin"
PASSWORD = "administrator"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def configure(host: Optional[str] = None, port: Optional[int] = None,
              username: Optional[str] = None, password: Optional[str] = None) -> None:
    """Point the shared Conn/port helpers at this suite's server and credentials."""
    global HOST, PORT, USERNAME, PASSWORD
    if host is not None:
        HOST = host
    if port is not None:
        PORT = port
    if username is not None:
        USERNAME = username
    if password is not None:
        PASSWORD = password


# ── result tracking ──────────────────────────────────────────────────────────

_failures = 0
_warnings = 0


def failure_count() -> int:
    return _failures


def warning_count() -> int:
    return _warnings


def record_failure() -> None:
    """Count a failure that has already been reported some other way."""
    global _failures
    _failures += 1


def check(label: str, ok: bool, detail: str = "") -> bool:
    """The one assertion primitive: every other check_* helper funnels into this."""
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
    """Assert both the status and the errorCode of an error response."""
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
    """Walk a dotted path through the response, indexing into lists by number."""
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
    """Assert a value inside the response payload (status is not enough)."""
    actual = dig(response, path)
    return check(label, actual == expected,
                 f"path={path}  expected={expected!r}  got={actual!r}")


# ── report chrome ────────────────────────────────────────────────────────────

def banner(title: str, host: Optional[str] = None, port: Optional[int] = None) -> None:
    """Opening block. Pass host/port for suites that attach to an already-running server."""
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
    """Print the closing block and exit 0/1. Never returns."""
    print("\n" + "═" * WIDTH)
    if _failures == 0:
        print(f"  {GREEN}All checks passed.{RESET}{suffix}")
    else:
        print(f"  {RED}{_failures} check(s) FAILED.{RESET}{suffix}")
        if on_failure is not None:
            on_failure()
    print("═" * WIDTH + "\n")

    sys.exit(0 if _failures == 0 else 1)


# ── the wire ─────────────────────────────────────────────────────────────────

class Conn:
    """One client connection. Use as a context manager; `with Conn() as c` yields self.

    `sock` lets a suite supply an already-connected socket (the TLS suite hands over an
    SSL-wrapped one); otherwise a plain TCP connection to host/port is opened.
    """

    def __init__(self, host: Optional[str] = None, port: Optional[int] = None,
                 timeout: float = 60.0, sock=None):
        if sock is None:
            sock = socket.create_connection((host or HOST, port or PORT), timeout=timeout)
        else:
            sock.settimeout(timeout)
        self.s = sock
        self.f = self.s.makefile("rb")

    def send(self, payload: dict, timeout: Optional[float] = None) -> dict:
        """Send one request, read one response line. Never raises on a dead connection."""
        if timeout is not None:
            self.s.settimeout(timeout)
        try:
            self.s.sendall((json.dumps(payload) + "\n").encode())
        except (BrokenPipeError, OSError):
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        try:
            raw = self.f.readline().decode().strip()
        except (OSError, ConnectionError):
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        if not raw:
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            # The server emits a plaintext line (e.g. "The command is not valid") when a
            # request fails to parse. Surface it instead of crashing the suite.
            return {"status": "ERROR", "message": raw}

    def recv(self, timeout: float = 2.0) -> Optional[dict]:
        """Read one unsolicited push, or None if nothing arrives within `timeout`.

        select() rather than a socket timeout: a timed-out read would leave the
        underlying SocketIO in its _timeout_occurred state and poison every
        subsequent read on the same BufferedReader.
        """
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


# ── server lifecycle ─────────────────────────────────────────────────────────

def port_open(host: Optional[str] = None, port: Optional[int] = None) -> bool:
    try:
        with socket.create_connection((host or HOST, port or PORT), timeout=0.5):
            return True
    except OSError:
        return False


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
    """Launch a server in `work_dir` and wait for its client port to accept."""
    jar = jar or os.path.join(REPO_ROOT, "target", "lwnrdb-1.0-SNAPSHOT.jar")
    log = open(log_path, "ab")
    proc = subprocess.Popen(["java", f"-Xmx{xmx}", "-jar", jar],
                            stdout=log, stderr=log, cwd=work_dir)
    deadline = time.time() + 60.0
    while time.time() < deadline:
        if port_open(host, port):
            # Give the accept loop a beat to be fully ready.
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
