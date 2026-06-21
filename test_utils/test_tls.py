"""End-to-end tests for TLS (secure connections).

Unlike the other suites, this script is **self-contained**: it starts its own
LWNRDB instance configured with TLS enabled, on a dedicated port and working
directory, so it never collides with a plaintext server CI may already be
running on 8989. It then verifies:

  * a self-signed keystore is generated on first start (no keystore configured),
    and the SECURITY WARNING is logged;
  * the server presents an untrusted (self-signed) certificate — strict
    verification fails, proving the dev fallback is in effect;
  * a TLS client completes the handshake and a full request/response round-trip
    works over the encrypted channel (proving requests are decrypted and
    responses encrypted end to end);
  * a plaintext client connecting to the TLS port is rejected (never receives a
    valid JSON response).

The server lifecycle is managed via a tracked subprocess handle (not pgrep), so
stopping it never touches an unrelated LWNRDB process.
"""

import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("TLS_TEST_PORT", "9443"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"
KEYSTORE_PASSWORD = "changeit"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

failures = 0

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Server launch is configurable: by default the suite runs against the JAR, but
# setting LWNRDB_SERVER_BIN to a path (e.g. the GraalVM native executable) makes
# it launch that binary instead. Native images honor -Xmx as a runtime arg.
SERVER_BIN = os.environ.get("LWNRDB_SERVER_BIN")


def server_argv(xmx: str):
    if SERVER_BIN:
        return [SERVER_BIN, f"-Xmx{xmx}"]
    return ["java", f"-Xmx{xmx}", "-jar", os.path.join(REPO_ROOT, JAR)]


# ── reporting helpers (mirrors the other suites) ────────────────────────────

def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


def check(label: str, ok: bool, detail: str = ""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


def check_status(label: str, response: dict, expected_status: str):
    actual = response.get("status")
    check(label, actual == expected_status,
          f"expected={expected_status}  got={actual}  msg={response.get('message', '')!r}")


# ── protocol helper ─────────────────────────────────────────────────────────

def send(s, f, payload: dict) -> dict:
    try:
        s.sendall((json.dumps(payload) + "\n").encode())
    except (BrokenPipeError, OSError):
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    return json.loads(raw)


# ── TLS client helpers ──────────────────────────────────────────────────────

def tls_socket(verify: bool):
    """Open a TLS connection. When verify=True, the self-signed server cert is
    validated against the system trust store (expected to fail)."""
    raw = socket.create_connection((HOST, PORT), timeout=10)
    if verify:
        ctx = ssl.create_default_context()
        # SAN contains DNS:localhost, so use it as the verification hostname.
        return ctx.wrap_socket(raw, server_hostname="localhost")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx.wrap_socket(raw, server_hostname="localhost")


# ── server lifecycle ────────────────────────────────────────────────────────

def write_config(work_dir: str):
    """Minimal lwnrdb.cfg enabling TLS. Missing keys fall back to bundled defaults."""
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        "tlsEnabled=true\n"
        "tlsKeystorePath=certs/lwnrdb.p12\n"
        f"tlsKeystorePassword={KEYSTORE_PASSWORD}\n"
        f"defaultAdminUsername={ADMIN_USERNAME}\n"
        f"defaultAdminPassword={ADMIN_PASSWORD}\n"
    )
    with open(os.path.join(work_dir, "lwnrdb.cfg"), "w") as fp:
        fp.write(cfg)


def port_open() -> bool:
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return True
    except OSError:
        return False


def start_server(work_dir: str, log_path: str):
    log = open(log_path, "ab")
    proc = subprocess.Popen(server_argv("512m"),
                            stdout=log, stderr=log, cwd=work_dir)
    deadline = time.time() + 60.0
    while time.time() < deadline:
        if port_open():
            # Give the accept loop a beat to be fully ready for handshakes.
            time.sleep(0.5)
            return proc
        if proc.poll() is not None:
            break
        time.sleep(0.2)
    _dump_log(log_path)
    proc.kill()
    raise RuntimeError("TLS server did not come up in time")


def stop_server(proc):
    if proc is None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()


def _dump_log(log_path: str):
    try:
        with open(log_path, "rb") as fp:
            tail = fp.read()[-4000:].decode(errors="replace")
        print(f"--- server log tail ---\n{tail}\n--- end ---", file=sys.stderr)
    except OSError:
        pass


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_keystore_generated(work_dir: str, log_path: str):
    section("Self-signed keystore generation on first start")

    keystore = os.path.join(work_dir, "certs", "lwnrdb.p12")
    check("Keystore file was generated at the configured path",
          os.path.isfile(keystore),
          f"path={keystore}")

    warned = False
    try:
        with open(log_path, "r", errors="replace") as fp:
            log = fp.read()
        warned = "SECURITY WARNING" in log and "TLS keystore" in log
    except OSError:
        pass
    check("Startup logged a SECURITY WARNING about the self-signed certificate", warned)


def test_self_signed_is_untrusted():
    section("Self-signed certificate is untrusted (strict verification fails)")

    failed_verification = False
    detail = "strict verification unexpectedly succeeded"
    try:
        with tls_socket(verify=True):
            pass
    except ssl.SSLCertVerificationError as e:
        failed_verification = True
        detail = f"rejected as expected: {e.verify_message or e}"
    except ssl.SSLError as e:
        failed_verification = True
        detail = f"rejected as expected: {e}"
    check("TLS client with default trust store rejects the self-signed cert",
          failed_verification, detail)


def test_tls_roundtrip():
    section("Full request/response round-trip over the encrypted channel")

    s = tls_socket(verify=False)
    try:
        peer_cert = s.getpeercert(binary_form=True)
        check("Handshake completed and server presented a certificate",
              peer_cert is not None and len(peer_cert) > 0,
              f"cipher={s.cipher()[0] if s.cipher() else 'none'}")

        f = s.makefile("rb")
        check_status("AUTHENTICATE as admin over TLS",
                     send(s, f, {"type": "AUTHENTICATE",
                                 "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}),
                     "OK")
        check_status("CREATE_DATABASE over TLS",
                     send(s, f, {"type": "CREATE_DATABASE", "databaseName": "tls_db"}),
                     "OK")
        check_status("CREATE_COLLECTION over TLS",
                     send(s, f, {"type": "CREATE_COLLECTION",
                                 "databaseName": "tls_db", "collectionName": "items"}),
                     "OK")
        check_status("SAVE over TLS",
                     send(s, f, {"type": "SAVE", "databaseName": "tls_db", "collectionName": "items",
                                 "object": {"_id": "doc1", "value": 42}}),
                     "OK")
        check_status("FIND_BY_ID over TLS",
                     send(s, f, {"type": "FIND_BY_ID", "databaseName": "tls_db",
                                 "collectionName": "items", "_id": "doc1"}),
                     "OK")
        check_status("AGGREGATE (COUNT) over TLS",
                     send(s, f, {"type": "AGGREGATE", "databaseName": "tls_db", "collectionName": "items",
                                 "aggregationSteps": [{"type": "COUNT"}]}),
                     "OK")
        check_status("DELETE over TLS",
                     send(s, f, {"type": "DELETE", "databaseName": "tls_db",
                                 "collectionName": "items", "_id": "doc1"}),
                     "OK")
        check_status("DROP_DATABASE over TLS (cleanup)",
                     send(s, f, {"type": "DROP_DATABASE", "databaseName": "tls_db"}),
                     "OK")
    finally:
        s.close()


def test_plaintext_rejected():
    section("Plaintext client is rejected by the TLS server")

    line = None
    try:
        with socket.create_connection((HOST, PORT), timeout=10) as plain:
            plain.settimeout(5)
            plain.sendall(b'{"type":"CLOSE_CONNECTION"}\n')
            raw = plain.makefile("rb").readline()
            line = raw.decode("utf-8", errors="replace").strip() if raw else None
    except OSError:
        # Connection reset during the failed handshake is also an acceptable rejection.
        line = None

    check("Plaintext client never receives a valid JSON response",
          line is None or "status" not in line,
          f"got={line!r}")

    # The server must survive the rejected plaintext client and still serve TLS.
    survived = False
    try:
        with tls_socket(verify=False) as s:
            f = s.makefile("rb")
            r = send(s, f, {"type": "LIST_DATABASES"})
            survived = r.get("status") == "OK"
    except OSError:
        survived = False
    check("Server still accepts a TLS client after rejecting plaintext", survived)


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 60)
    print("  LWNRDB — TLS / secure connections test suite")
    print("═" * 60)

    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-tls-")
    log_path = os.path.join(work_dir, "server.log")
    write_config(work_dir)
    print(f"  Working dir: {work_dir}")
    print(f"  Starting TLS server on {HOST}:{PORT} ...")

    proc = None
    try:
        proc = start_server(work_dir, log_path)
        test_keystore_generated(work_dir, log_path)
        test_self_signed_is_untrusted()
        test_tls_roundtrip()
        test_plaintext_rejected()
    finally:
        stop_server(proc)

    print("\n" + "═" * 60)
    if failures == 0:
        print("  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
        _dump_log(log_path)
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
