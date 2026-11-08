import os
import shlex
import shutil
import subprocess
import tempfile
import time

import base_utils as bu

IMAGE = os.environ.get("DISK_FULL_IMAGE", "lwnrdb-disk-full")
DATA = "/app/data"
LOGS = "/app/logs"
DATA_BALLAST = f"{DATA}/ballast"
LOGS_BALLAST = f"{LOGS}/ballast"
OUTPUT = "/app/server.out"
PID = "/app/server.pid"
JAVA = "java -XX:+UseG1GC -Xmx512m -Djdk.nio.maxCachedBufferSize=262144 -jar app.jar"
DATA_SIZE = "48m"
LOGS_SIZE = "8m"
BLOCK = 4096


class Container:

    def __init__(self, name: str, port: int, settings: dict):
        self.name = name
        self.port = port
        self.settings = settings
        self.cfg_dir = tempfile.mkdtemp(prefix="lwnrdb-disk-full-")

    def _write_cfg(self):
        lines = [f"port={self.port}", f"filePath={DATA}/db", f"logPath={LOGS}",
                 f"defaultAdminUsername={bu.USERNAME}", f"defaultAdminPassword={bu.PASSWORD}"]
        lines += [f"{key}={str(value).lower() if isinstance(value, bool) else value}"
                  for key, value in self.settings.items()]
        path = os.path.join(self.cfg_dir, "lwnrdb.cfg")
        with open(path, "w") as fp:
            fp.write("\n".join(lines) + "\n")
        os.chmod(path, 0o600)
        return path

    def up(self):
        self.remove()
        cfg = self._write_cfg()
        subprocess.run(["docker", "run", "-d", "--init", "--name", self.name,
                        "-p", f"127.0.0.1:{self.port}:{self.port}",
                        "--tmpfs", f"{DATA}:rw,size={DATA_SIZE},mode=0700",
                        "--tmpfs", f"{LOGS}:rw,size={LOGS_SIZE},mode=0700",
                        "-v", f"{cfg}:/app/lwnrdb.cfg:ro",
                        IMAGE, "sleep", "infinity"], check=True, capture_output=True)

    def remove(self):
        subprocess.run(["docker", "rm", "-f", self.name], capture_output=True)

    def sh(self, command: str, timeout: float = 120.0) -> subprocess.CompletedProcess:
        return subprocess.run(["docker", "exec", self.name, "sh", "-c", command],
                              capture_output=True, timeout=timeout)

    def text(self, command: str, timeout: float = 120.0) -> str:
        return self.sh(command, timeout).stdout.decode(errors="replace")

    def ready(self) -> bool:
        try:
            with bu.Conn(port=self.port, timeout=3.0) as conn:
                return conn.authenticate().get("status") == "OK"
        except OSError:
            return False

    def alive(self) -> bool:
        return self.sh(f"kill -0 $(cat {PID} 2>/dev/null) 2>/dev/null").returncode == 0

    def start_server(self, timeout_s: float = 90.0) -> bool:
        self.sh(f"cd /app && ({JAVA} >> {OUTPUT} 2>&1 & echo $! > {PID})")
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if self.ready():
                return True
            if not self.alive():
                return False
            time.sleep(0.3)
        return False

    def wait_exit(self, timeout_s: float) -> bool:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if not self.alive():
                return True
            time.sleep(0.2)
        return not self.alive()

    def stop_server(self, timeout_s: float = 40.0) -> bool:
        self.sh(f"kill -TERM $(cat {PID}) 2>/dev/null")
        if self.wait_exit(timeout_s):
            return True
        self.kill_server()
        return False

    def kill_server(self):
        self.sh(f"kill -KILL $(cat {PID}) 2>/dev/null")
        self.wait_exit(10.0)

    def output(self) -> str:
        return self.text(f"cat {OUTPUT} 2>/dev/null")

    def free_blocks(self, mount: str) -> int:
        fields = self.text(f"stat -f -c '%a %S' {mount}").split()
        return int(fields[0]) * int(fields[1]) // BLOCK

    def grow(self, ballast: str, blocks: int) -> bool:
        if blocks <= 0:
            return True
        result = self.sh(f"dd if=/dev/zero of={ballast} bs={BLOCK} count={blocks} oflag=append conv=notrunc")
        return result.returncode == 0

    def fill_to(self, mount: str, ballast: str, headroom_bytes: int) -> int:
        keep = -(-headroom_bytes // BLOCK)
        for _ in range(4):
            self.grow(ballast, self.free_blocks(mount) - keep)
        return self.free_blocks(mount) * BLOCK

    def free(self, ballast: str):
        self.sh(f"rm -f {ballast}")

    def snapshot(self, dest: str, attempts: int = 5) -> bool:
        for _ in range(attempts):
            if self.copy_data_once(dest):
                return True
            time.sleep(0.5)
        return False

    def copy_data_once(self, dest: str) -> bool:
        shutil.rmtree(dest, ignore_errors=True)
        os.makedirs(dest, exist_ok=True)
        tar = subprocess.Popen(["docker", "exec", self.name, "tar", "-C", DATA, "-cf", "-", "db"],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        untar = subprocess.run(["tar", "-xf", "-", "-C", dest], stdin=tar.stdout, capture_output=True)
        tar.stdout.close()
        return tar.wait() == 0 and untar.returncode == 0

    def checksums(self) -> dict:
        listing = self.text(f"cd {DATA} && find db -type f -exec md5sum {{}} + 2>/dev/null")
        sums = {}
        for line in listing.splitlines():
            digest, _, path = line.partition("  ")
            sums[path] = digest
        return sums

    def describe(self) -> str:
        return " ".join(shlex.quote(part) for part in ["docker", "exec", "-it", self.name, "sh"])
