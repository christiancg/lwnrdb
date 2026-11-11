from __future__ import annotations

import re
import subprocess
from collections.abc import Iterator

import pytest

from lwnrdb import NO_RETRIES, AsyncLwnrdbClient, InsecureTlsWarning, LwnrdbError, TlsSettings
from tests.it.conftest import settings_for
from tests.support.server_process import ServerProcess

pytestmark = pytest.mark.it
STORE_PASSWORD = "change_it"


@pytest.fixture(scope="module")
def server() -> Iterator[ServerProcess]:
    started = ServerProcess.start(
        {"tlsEnabled": "true", "tlsKeystorePath": "certs/lwnrdb.p12", "tlsKeystorePassword": STORE_PASSWORD}
    )
    yield started
    started.stop()


def _keytool(*arguments: str) -> str:
    return subprocess.run(["keytool", *arguments], capture_output=True, text=True, check=True).stdout


def _exported_certificate(server: ServerProcess) -> str:
    keystore = str(server.work_dir / "certs" / "lwnrdb.p12")
    store = ("-keystore", keystore, "-storepass", STORE_PASSWORD, "-storetype", "PKCS12")
    match = re.search(r"^([^,\s]+),.*PrivateKeyEntry", _keytool("-list", *store), re.MULTILINE)
    assert match is not None
    return _keytool("-exportcert", "-rfc", "-alias", match.group(1), *store)


async def test_an_insecure_client_connects_over_tls(server: ServerProcess) -> None:
    with pytest.warns(InsecureTlsWarning):
        client = await AsyncLwnrdbClient.connect(settings_for(server, tls=TlsSettings(insecure=True)))
    async with client:
        assert (await client.server_info()).state


async def test_a_client_trusting_the_exported_certificate_connects(server: ServerProcess) -> None:
    trusted = TlsSettings(ca_data=_exported_certificate(server), server_hostname="localhost")
    async with await AsyncLwnrdbClient.connect(settings_for(server, tls=trusted)) as client:
        assert (await client.server_info()).version


async def test_system_trust_and_plaintext_clients_are_refused(server: ServerProcess) -> None:
    with pytest.raises(LwnrdbError):
        await AsyncLwnrdbClient.connect(settings_for(server, tls=TlsSettings(), connect_timeout=5))
    with pytest.raises(LwnrdbError):
        await AsyncLwnrdbClient.connect(settings_for(server, request_timeout=5, retry_policy=NO_RETRIES))
