import os
import time

from disk_full.container import DATA, LOGS, LOGS_SIZE, Container

IMAGE = os.environ.get("LAZYFS_IMAGE", "lwnrdb-lazyfs")
ROOT = "/app/lazyfs-root"
CONFIG = "/app/lazyfs.toml"
FAULTS_FIFO = "/tmp/lazyfs.fifo"
COMPLETED_FIFO = "/tmp/lazyfs.completed.fifo"
LAZYFS_LOG = "/tmp/lazyfs.log"
MOUNT_WAIT_S = 15.0
CLEAR_WAIT_S = 120.0
PROBE = "/tmp/ForceProbe.java"
PROBE_SOURCE = """import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ForceProbe {
    public static void main(String[] args) throws Exception {
        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(args[1].getBytes(StandardCharsets.UTF_8)));
            if (args.length > 2) {
                channel.force(true);
            }
        }
    }
}
"""
SMOKE_FORCED = "smoke-forced"
SMOKE_UNFORCED = "smoke-unforced"


class LazyFsDied(RuntimeError):
    pass


class LazyFsContainer(Container):

    def __init__(self, name: str, port: int, settings: dict):
        super().__init__(name, port, settings, image=IMAGE)

    def run_args(self) -> list:
        return ["--device", "/dev/fuse", "--cap-add", "SYS_ADMIN", "--security-opt", "apparmor=unconfined",
                "--tmpfs", f"{LOGS}:rw,size={LOGS_SIZE},mode=0700"]

    def mount(self):
        self.sh(f"mkdir -p {ROOT} {DATA} && chmod 0700 {ROOT}")
        self.sh(f"lazyfs {DATA} --config-path {CONFIG} -o allow_other -o modules=subdir -o subdir={ROOT}"
                f" -f > {LAZYFS_LOG} 2>&1 &")
        deadline = time.time() + MOUNT_WAIT_S
        while time.time() < deadline:
            if self.sh(f"mountpoint -q {DATA}").returncode == 0:
                self.sh(f"chmod 0700 {DATA}")
                return
            time.sleep(0.2)
        raise RuntimeError(f"LazyFS did not mount {DATA}: {self.text(f'cat {LAZYFS_LOG}')[-1500:]}")

    def lazyfs_running(self) -> bool:
        return self.sh("pgrep -x lazyfs").returncode == 0

    def clear_cache(self):
        if not self.lazyfs_running():
            raise LazyFsDied(f"LazyFS is no longer running: {self.text(f'tail -c 1500 {LAZYFS_LOG}')}")
        wait = f"timeout {CLEAR_WAIT_S:.0f}"
        finished = self.text(f"exec 3<> {COMPLETED_FIFO}; {wait} sh -c 'echo lazyfs::clear-cache > {FAULTS_FIFO}';"
                             f" {wait} head -n1 <&3; exec 3<&-", timeout=2 * CLEAR_WAIT_S + 10)
        if "finished::clear-cache" not in finished:
            raise RuntimeError(f"LazyFS did not finish clear-cache within {CLEAR_WAIT_S:.0f} s: {finished!r}\n"
                               f"{self.lazyfs_state()}")

    def lazyfs_state(self) -> str:
        return self.text("L=$(pgrep -x lazyfs); for t in /proc/$L/task/*; do echo \"$(cat $t/comm) $(cat $t/wchan)\";"
                         f" head -4 $t/stack 2>/dev/null; done; tail -6 {LAZYFS_LOG}")

    def smoke(self) -> tuple:
        self.sh(f"cat > {PROBE} <<'EOF'\n{PROBE_SOURCE}EOF")
        forced, unforced = f"{DATA}/{SMOKE_FORCED}", f"{DATA}/{SMOKE_UNFORCED}"
        self.sh(f"rm -f {forced} {unforced}")
        self.sh(f"java {PROBE} {forced} {SMOKE_FORCED} force && java {PROBE} {unforced} {SMOKE_UNFORCED}")
        self.clear_cache()
        kept = self.text(f"cat {forced} 2>/dev/null")
        dropped = self.text(f"cat {unforced} 2>/dev/null")
        self.sh(f"rm -f {forced} {unforced}")
        passed = kept == SMOKE_FORCED and dropped != SMOKE_UNFORCED
        return passed, f"forced write kept {kept!r}, unforced write left {dropped!r}"

    def unmount(self):
        self.sh(f"umount {DATA} 2>/dev/null || fusermount3 -u {DATA} 2>/dev/null")

    def remove(self):
        if self.sh("true").returncode == 0:
            self.unmount()
        super().remove()
