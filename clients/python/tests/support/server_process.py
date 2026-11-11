from __future__ import annotations

import os
import shutil
import socket
import subprocess
import tempfile
import time
from collections.abc import Mapping
from pathlib import Path

ADMIN = "admin"
PASSWORD = "Tst-Admin-9xQ2mV"
_STARTUP_SECONDS = 90.0
_STOP_SECONDS = 30.0
_DEFAULT_JAR = Path(__file__).resolve().parents[4] / "target" / "lwnrdb.jar"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        port: int = probe.getsockname()[1]
        return port


def _accepts(port: int) -> bool:
    try:
        with socket.create_connection(("localhost", port), timeout=1.0):
            return True
    except OSError:
        return False


class ServerProcess:
    def __init__(self, port: int, work_dir: Path, process: subprocess.Popen[bytes]) -> None:
        self.port = port
        self.work_dir = work_dir
        self._process = process

    @classmethod
    def start(cls, overrides: Mapping[str, str] | None = None) -> ServerProcess:
        jar = Path(os.environ.get("LWNRDB_JAR", str(_DEFAULT_JAR))).resolve()
        if not jar.exists():
            raise RuntimeError(f"no server jar at {jar}; run mvn package -DskipTests at the repository root")
        work_dir = Path(tempfile.mkdtemp(prefix="lwnrdb-it-"))
        port = free_port()
        config = {
            "port": str(port),
            "filePath": "db",
            "logPath": "logs",
            "defaultAdminPassword": PASSWORD,
            "maxMemory": "128Mb",
            **(overrides or {}),
        }
        (work_dir / "lwnrdb.cfg").write_text("".join(f"{key}={value}\n" for key, value in config.items()))
        with (work_dir / "server.out").open("wb") as output:
            process = subprocess.Popen(
                ["java", "-Xmx512m", "-XX:+UseG1GC", "-jar", str(jar)],
                cwd=work_dir,
                stdout=output,
                stderr=subprocess.STDOUT,
            )
        server = cls(port, work_dir, process)
        server._await_port()
        return server

    def output(self) -> str:
        return (self.work_dir / "server.out").read_text(errors="replace")

    def stop(self) -> None:
        if self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(_STOP_SECONDS)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait()
        shutil.rmtree(self.work_dir, ignore_errors=True)

    def _await_port(self) -> None:
        deadline = time.monotonic() + _STARTUP_SECONDS
        while time.monotonic() < deadline:
            if self._process.poll() is not None:
                raise RuntimeError(f"the server exited: {self.output()}")
            if _accepts(self.port):
                return
            time.sleep(0.2)
        self._process.kill()
        raise RuntimeError(f"the server did not open port {self.port} within {_STARTUP_SECONDS} s")
