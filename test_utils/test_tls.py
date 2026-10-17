import os
import socket
import ssl
import stat
import tempfile
import threading
import time

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("TLS_TEST_PORT", "9443"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD
KEYSTORE_PASSWORD = "changeit"
AUTH_TIMEOUT_S = 2.0
PRE_AUTH_PER_ADDRESS = 4

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def tls_socket(verify: bool):
    raw = socket.create_connection((HOST, PORT), timeout=10)
    if verify:
        ctx = ssl.create_default_context()
        return ctx.wrap_socket(raw, server_hostname="localhost")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx.wrap_socket(raw, server_hostname="localhost")


def write_config(work_dir: str):
    bu.write_config(work_dir, PORT, tlsEnabled=True, tlsKeystorePath="certs/lwnrdb.p12",
                    tlsKeystorePassword=KEYSTORE_PASSWORD, authTimeoutMs=int(AUTH_TIMEOUT_S * 1000),
                    maxUnauthenticatedConnectionsPerAddress=PRE_AUTH_PER_ADDRESS)


def closed_by_server_within(sock: socket.socket, seconds: float) -> bool:
    sock.settimeout(seconds)
    try:
        while sock.recv(4096):
            pass
        return True
    except (ConnectionResetError, BrokenPipeError):
        return True
    except OSError:
        return False


def test_a_silent_peer_is_closed_at_the_handshake_deadline():
    section("A peer that never starts the TLS handshake is closed at the deadline")
    with socket.create_connection((HOST, PORT), timeout=10) as silent:
        started = time.time()
        closed = closed_by_server_within(silent, AUTH_TIMEOUT_S + 4)
        check("The server closes it", closed)
        check("Within authTimeoutMs plus slack", time.time() - started < AUTH_TIMEOUT_S + 4,
              f"after {time.time() - started:.1f}s")


def test_a_dripped_client_hello_is_closed_at_the_handshake_deadline():
    section("A ClientHello dripped a byte at a time does not outlive the deadline")
    with socket.create_connection((HOST, PORT), timeout=10) as dripping:
        dripping.sendall(bytes([0x16, 0x03, 0x01, 0x01, 0xF0]))
        stop = threading.Event()

        def drip():
            while not stop.wait(0.3):
                try:
                    dripping.sendall(b"\x01")
                except OSError:
                    return

        dripper = threading.Thread(target=drip, daemon=True)
        dripper.start()
        started = time.time()
        closed = closed_by_server_within(dripping, AUTH_TIMEOUT_S + 4)
        stop.set()
        check("The server closes the dripping peer", closed)
        check("Within authTimeoutMs plus slack", time.time() - started < AUTH_TIMEOUT_S + 4,
              f"after {time.time() - started:.1f}s")


def test_one_address_cannot_hold_every_unauthenticated_slot():
    section("One address holds at most maxUnauthenticatedConnectionsPerAddress unauthenticated connections; "
            "the next TLS one is closed before its handshake")
    holders = [socket.create_connection((HOST, PORT), timeout=10) for _ in range(PRE_AUTH_PER_ADDRESS)]
    try:
        time.sleep(0.3)
        outcome = None
        try:
            with tls_socket(verify=False) as extra:
                extra.settimeout(5)
                raw = extra.makefile("rb").readline()
                outcome = "handshake completed, then: " + (raw.decode("utf-8", errors="replace").strip() or "EOF")
        except (ssl.SSLError, OSError) as refused:
            outcome = f"closed before the handshake ({type(refused).__name__})"
        check("The next connection from the same address is closed before its TLS handshake",
              outcome is not None and outcome.startswith("closed before the handshake"), f"got={outcome!r}")
    finally:
        for holder in holders:
            holder.close()
    time.sleep(AUTH_TIMEOUT_S + 1.5)
    with tls_socket(verify=False) as fresh:
        conn = Conn(sock=fresh)
        conn.authenticate()
        check_status("Once they are gone a new client authenticates", conn.send({"type": "LIST_DATABASES"}), "OK")


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

        c = Conn(sock=s)
        check_status("AUTHENTICATE as admin over TLS", c.authenticate(), "OK")
        check_status("CREATE_DATABASE over TLS",
                     c.send({"type": "CREATE_DATABASE", "databaseName": "tls_db"}),
                     "OK")
        check_status("CREATE_COLLECTION over TLS",
                     c.send({"type": "CREATE_COLLECTION",
                             "databaseName": "tls_db", "collectionName": "items"}),
                     "OK")
        check_status("SAVE over TLS",
                     c.send({"type": "SAVE", "databaseName": "tls_db", "collectionName": "items",
                             "object": {"_id": "doc1", "value": 42}}),
                     "OK")
        check_status("FIND_BY_ID over TLS",
                     c.send({"type": "FIND_BY_ID", "databaseName": "tls_db",
                             "collectionName": "items", "_id": "doc1"}),
                     "OK")
        check_status("AGGREGATE (COUNT) over TLS",
                     c.send({"type": "AGGREGATE", "databaseName": "tls_db", "collectionName": "items",
                             "aggregationSteps": [{"type": "COUNT"}]}),
                     "OK")
        check_status("DELETE over TLS",
                     c.send({"type": "DELETE", "databaseName": "tls_db",
                             "collectionName": "items", "_id": "doc1"}),
                     "OK")
        check_status("DROP_DATABASE over TLS (cleanup)",
                     c.send({"type": "DROP_DATABASE", "databaseName": "tls_db"}),
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
        line = None

    check("Plaintext client never receives a valid JSON response",
          line is None or "status" not in line,
          f"got={line!r}")

    survived = False
    try:
        with tls_socket(verify=False) as s:
            conn = Conn(sock=s)
            conn.authenticate()
            r = conn.send({"type": "LIST_DATABASES"})
            survived = r.get("status") == "OK"
    except OSError:
        survived = False
    check("Server still accepts a TLS client after rejecting plaintext", survived)


def fresh_work_dir(prefix: str):
    work_dir = tempfile.mkdtemp(prefix=f"lwnrdb-{prefix}-")
    return work_dir, os.path.join(work_dir, "server.log")


def check_refused_start(label: str, work_dir: str, log_path: str, expected: str, storage_untouched: bool):
    code = bu.run_until_exit(work_dir, log_path)
    log = bu.read_log(log_path)
    check(f"{label}: the server exits non-zero", code is not None and code != 0, f"exit={code}")
    check(f"{label}: a FATAL line names defaultAdminPassword",
          "FATAL" in log and "defaultAdminPassword" in log and "will not start" in log and expected in log,
          next((line for line in log.splitlines() if "FATAL" in line), log[-600:]))
    check(f"{label}: no port was opened", not bu.port_open())
    if storage_untouched:
        check(f"{label}: the admin database was never created",
              not os.path.isdir(os.path.join(work_dir, "db", "admin")))


def test_default_admin_password_refuses_to_start():
    section("The shipped admin password refuses to start")
    work_dir, log_path = fresh_work_dir("shipped-pw")
    bu.write_config(work_dir, PORT, admin_password="administrator")
    check_refused_start("defaultAdminPassword=administrator", work_dir, log_path, "well-known shipped value", True)


def test_weak_admin_password_refuses_to_start():
    section("A commonly used or username-equal admin password refuses to start")
    work_dir, log_path = fresh_work_dir("weak-pw")
    bu.write_config(work_dir, PORT, admin_password="Password123")
    check_refused_start("defaultAdminPassword=Password123", work_dir, log_path, "most commonly used passwords", True)
    work_dir, log_path = fresh_work_dir("username-pw")
    bu.write_config(work_dir, PORT, admin_password="ADMIN_admin", defaultAdminUsername="admin_ADMIN")
    check_refused_start("defaultAdminPassword equal to the username", work_dir, log_path,
                        "same as defaultAdminUsername", True)


def test_cluster_tls_keystore_and_disabled_limits_are_warned_about():
    section("Cluster-only TLS warns about the shipped keystore password, and 0 limits are named")
    work_dir, log_path = fresh_work_dir("cluster-tls-warn")
    bu.write_config(work_dir, PORT, clusterEnabled=True, clusterTlsEnabled=True, clusterExpectedSize=2,
                    clusterPort=PORT + 1000, clusterSecret="cluster-secret-for-the-tls-suite-0123456789",
                    tlsKeystorePath="certs/lwnrdb.p12", tlsKeystorePassword="change_it", maxListens=0,
                    maxFilterOperands=0)
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path)
    finally:
        bu.stop_server(proc)
    log = bu.read_log(log_path)
    check("tlsKeystorePassword=change_it is warned about with only cluster TLS on",
          "tlsKeystorePassword is still the shipped value 'change_it'" in log)
    zero_line = next((line for line in log.splitlines() if "limits are set to 0" in line), "")
    check("every protective limit set to 0 is named in one SECURITY WARNING",
          "SECURITY WARNING" in zero_line and "maxListens" in zero_line and "maxFilterOperands" in zero_line
          and "maxConnections" not in zero_line, zero_line or log[-600:])


def connects(address: str, port: int) -> bool:
    try:
        socket.create_connection((address, port), timeout=3).close()
        return True
    except OSError:
        return False


def address_a_loopback_listener_refuses():
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("10.255.255.255", 1))
        address = probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()
    if address.startswith("127."):
        return None
    with socket.socket() as control:
        control.bind(("127.0.0.1", 0))
        control.listen()
        reachable = connects(address, control.getsockname()[1])
    return None if reachable else address


def test_bind_address_and_tenant_folders():
    section("bindAddress limits the client port, and tenant folders are created owner-only")
    work_dir, log_path = fresh_work_dir("bind-address")
    bu.write_config(work_dir, PORT, bindAddress="127.0.0.1")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path)
        with Conn() as c:
            check_status("a loopback client authenticates", c.authenticate(), "OK")
            check_status("CREATE_DATABASE", c.send({"type": "CREATE_DATABASE", "databaseName": "tls_owner_db"}), "OK")
            check_status("CREATE_COLLECTION", c.send({"type": "CREATE_COLLECTION", "databaseName": "tls_owner_db",
                                                      "collectionName": "tls_owner_coll"}), "OK")
        external = address_a_loopback_listener_refuses()
        if external is None:
            bu.warn("no non-loopback IPv4 address that a loopback-only listener refuses; the check is skipped")
        else:
            check(f"a client reaching {external} is refused", not connects(external, PORT))
    finally:
        bu.stop_server(proc)
    if os.name != "posix":
        return
    for folder in (os.path.join("db", "tls_owner_db"), os.path.join("db", "tls_owner_db", "tls_owner_coll"),
                   os.path.join("db", "admin", "pages")):
        path = os.path.join(work_dir, folder)
        check(f"{folder}/ was created 0700", mode_of(path) == 0o700, oct(mode_of(path)))


def test_blank_admin_password_refuses_to_bootstrap():
    section("A blank admin password refuses to bootstrap the first admin")
    work_dir, log_path = fresh_work_dir("blank-pw")
    bu.write_config(work_dir, PORT, admin_password="")
    check_refused_start("blank defaultAdminPassword", work_dir, log_path, "no admin user exists", False)


def test_blank_admin_password_starts_once_an_admin_exists():
    section("A blank admin password starts once an admin exists, and plaintext is warned about")
    work_dir, log_path = fresh_work_dir("blank-after-admin")
    bu.write_config(work_dir, PORT)
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path)
        bu.stop_server(proc)
        bu.write_config(work_dir, PORT, admin_password="")
        proc = bu.start_server(work_dir, log_path)
        with Conn() as c:
            check_status("AUTHENTICATE with the original password after restarting blank", c.authenticate(), "OK")
        with Conn() as c:
            r = c.authenticate(ADMIN_USERNAME, "")
            check("A blank password does not authenticate", r.get("status") != "OK", str(r))
    finally:
        bu.stop_server(proc)
    log = bu.read_log(log_path)
    check("tlsEnabled=false logs a plaintext SECURITY WARNING",
          "SECURITY WARNING: tlsEnabled=false" in log)


def test_default_keystore_password_warning(main_log_path: str):
    section("The shipped keystore password is warned about with TLS on")
    check("A custom keystore password logs no change_it warning",
          "tlsKeystorePassword is still the shipped value" not in bu.read_log(main_log_path))
    check("A TLS server logs no plaintext warning",
          "SECURITY WARNING: tlsEnabled=false" not in bu.read_log(main_log_path))
    work_dir, log_path = fresh_work_dir("change-it")
    bu.write_config(work_dir, PORT, tlsEnabled=True, tlsKeystorePath="certs/lwnrdb.p12",
                    tlsKeystorePassword="change_it")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path)
    finally:
        bu.stop_server(proc)
    check("tlsKeystorePassword=change_it logs a SECURITY WARNING",
          "tlsKeystorePassword is still the shipped value 'change_it'" in bu.read_log(log_path))


def mode_of(path: str) -> int:
    return stat.S_IMODE(os.stat(path).st_mode)


def test_data_and_keystore_folders_are_owner_only(work_dir: str, log_path: str):
    section("Created data, log and keystore paths are owner-only")
    if os.name != "posix":
        bu.warn("POSIX permissions are not available on this host; skipped")
        return
    for folder in ("db", "logs", "certs"):
        path = os.path.join(work_dir, folder)
        check(f"{folder}/ was created 0700", mode_of(path) == 0o700, oct(mode_of(path)))
    keystore = os.path.join(work_dir, "certs", "lwnrdb.p12")
    check("The generated keystore is 0600", mode_of(keystore) == 0o600, oct(mode_of(keystore)))
    data_root = os.path.join(work_dir, "db")
    os.chmod(data_root, 0o755)
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path)
    finally:
        bu.stop_server(proc)
    log = bu.read_log(log_path)
    check("A group/world-accessible data root is warned about at startup",
          "data folder (filePath)" in log and "chmod 700" in log)
    check("The warning never chmod-ed the existing data root", mode_of(data_root) == 0o755, oct(mode_of(data_root)))


def main():
    bu.banner("TLS / secure connections test suite")

    bu.require_jar()

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-tls-")
    log_path = os.path.join(work_dir, "server.log")
    write_config(work_dir)
    print(f"  Working dir: {work_dir}")
    print(f"  Starting TLS server on {HOST}:{PORT} ...")

    proc = None
    try:
        proc = bu.start_server(work_dir, log_path)
        test_keystore_generated(work_dir, log_path)
        test_self_signed_is_untrusted()
        test_tls_roundtrip()
        test_plaintext_rejected()
        test_a_silent_peer_is_closed_at_the_handshake_deadline()
        test_a_dripped_client_hello_is_closed_at_the_handshake_deadline()
        test_one_address_cannot_hold_every_unauthenticated_slot()
    finally:
        bu.stop_server(proc)

    test_data_and_keystore_folders_are_owner_only(work_dir, log_path)
    test_default_keystore_password_warning(log_path)
    test_default_admin_password_refuses_to_start()
    test_weak_admin_password_refuses_to_start()
    test_cluster_tls_keystore_and_disabled_limits_are_warned_about()
    test_bind_address_and_tenant_folders()
    test_blank_admin_password_refuses_to_bootstrap()
    test_blank_admin_password_starts_once_an_admin_exists()

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
