import os
import socket
import ssl
import tempfile

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("TLS_TEST_PORT", "9443"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"
KEYSTORE_PASSWORD = "changeit"

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
                    tlsKeystorePassword=KEYSTORE_PASSWORD)


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
            r = Conn(sock=s).send({"type": "LIST_DATABASES"})
            survived = r.get("status") == "OK"
    except OSError:
        survived = False
    check("Server still accepts a TLS client after rejecting plaintext", survived)


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
    finally:
        bu.stop_server(proc, wait_for_port=False)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
