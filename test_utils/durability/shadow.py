import os
import random
import re
import shutil
from dataclasses import dataclass, field

STRICT = "strict"
TORN = "torn"
EAGER = "eager"
MODES = (STRICT, TORN, EAGER)

BLOBS = "blobs"
LISTINGS = "listings"
LISTING_FILE = ".listing"
KEY_MAP = "keys.tsv"
SELF = "self"
FOLDER = "d"
FILE = "f"
BLOCK = 4096
INODE = re.compile(r"ino=(\d+)")


@dataclass
class RebuildReport:
    mode: str = STRICT
    files_dropped: list = field(default_factory=list)
    files_truncated: list = field(default_factory=list)
    files_emptied: list = field(default_factory=list)
    torn_blocks_kept: int = 0

    def summary(self) -> str:
        return (f"{self.mode} rebuild: dropped {len(self.files_dropped)} {self.files_dropped[:8]},"
                f" truncated {len(self.files_truncated)} {self.files_truncated[:8]},"
                f" never forced {len(self.files_emptied)} {self.files_emptied[:8]},"
                f" torn blocks kept {self.torn_blocks_kept}")


@dataclass
class Entry:
    name: str
    kind: str
    key_id: str
    file_key: str


def read_listing(shadow_root: str, relative: str):
    path = os.path.join(shadow_root, LISTINGS, relative, LISTING_FILE)
    if not os.path.isfile(path):
        return None, []
    own = None
    entries = []
    with open(path, "r", encoding="utf-8") as fp:
        for line in fp.read().splitlines():
            fields = line.split("\t")
            if len(fields) != 4:
                continue
            entry = Entry(*fields)
            if entry.name == SELF:
                own = entry.key_id
            else:
                entries.append(entry)
    return own, entries


def _blob(shadow_root: str, key_id: str):
    path = os.path.join(shadow_root, BLOBS, key_id)
    if not os.path.isfile(path):
        return None
    with open(path, "rb") as fp:
        return fp.read()


def _inode_of(file_key: str):
    match = INODE.search(file_key)
    return int(match.group(1)) if match else None


def _live_of(live_path: str, file_key: str):
    try:
        stat = os.stat(live_path, follow_symlinks=False)
    except OSError:
        return None
    if not os.path.isfile(live_path) or stat.st_ino != _inode_of(file_key):
        return None
    with open(live_path, "rb") as fp:
        return fp.read()


def torn_content(forced: bytes, live: bytes, rng: random.Random) -> tuple:
    if len(live) < len(forced):
        return forced, 0
    length = rng.randint(len(forced), len(live))
    result = bytearray(forced) + bytearray(max(0, length - len(forced)))
    kept = 0
    for start in range(0, length, BLOCK):
        end = min(start + BLOCK, length)
        live_block = live[start:end].ljust(end - start, b"\0") if start < len(live) else bytes(end - start)
        if bytes(result[start:end]) != live_block and rng.random() < 0.5:
            result[start:end] = live_block
            kept += 1
    return bytes(result), kept


def _restore_file(shadow_root, live_path, out_path, entry, mode, rng, report, relative):
    forced = _blob(shadow_root, entry.key_id)
    if forced is None:
        report.files_emptied.append(relative)
        forced = b""
    content = forced
    live = _live_of(live_path, entry.file_key) if mode == TORN else None
    if live is not None:
        content, kept = torn_content(forced, live, rng)
        report.torn_blocks_kept += kept
    if os.path.isfile(live_path) and os.path.getsize(live_path) > len(content):
        report.files_truncated.append(relative)
    with open(out_path, "wb") as fp:
        fp.write(content)
    os.chmod(out_path, 0o600)


def _restore_folder(shadow_root, data_root, staging, relative, expected_id, mode, rng, report, restored):
    own, entries = read_listing(shadow_root, relative)
    if own is None or (expected_id is not None and own != expected_id):
        return
    for entry in entries:
        child = os.path.join(relative, entry.name) if relative else entry.name
        out_path = os.path.join(staging, child)
        restored.add(child)
        if entry.kind == FOLDER:
            os.makedirs(out_path, mode=0o700, exist_ok=True)
            os.chmod(out_path, 0o700)
            _restore_folder(shadow_root, data_root, staging, child, entry.key_id, mode, rng, report, restored)
        elif entry.kind == FILE:
            _restore_file(shadow_root, os.path.join(data_root, child), out_path, entry, mode, rng, report, child)


def _live_files(data_root: str) -> list:
    found = []
    for root, _, files in os.walk(data_root):
        for name in files:
            found.append(os.path.relpath(os.path.join(root, name), data_root))
    return sorted(found)


def _ids_by_inode(shadow_root: str) -> dict:
    path = os.path.join(shadow_root, BLOBS, KEY_MAP)
    ids = {}
    if not os.path.isfile(path):
        return ids
    with open(path, "r", encoding="utf-8") as fp:
        for line in fp.read().splitlines():
            fields = line.split("\t")
            inode = _inode_of(fields[1]) if len(fields) == 2 else None
            if inode is not None:
                ids[inode] = fields[0]
    return ids


def _restore_eager(shadow_root: str, data_root: str, staging: str, report: RebuildReport) -> None:
    ids_by_inode = _ids_by_inode(shadow_root)
    for root, folders, _ in os.walk(data_root):
        for folder in folders:
            os.makedirs(os.path.join(staging, os.path.relpath(os.path.join(root, folder), data_root)), mode=0o700,
                        exist_ok=True)
    for relative in _live_files(data_root):
        live_path = os.path.join(data_root, relative)
        key_id = ids_by_inode.get(os.stat(live_path, follow_symlinks=False).st_ino)
        forced = _blob(shadow_root, key_id) if key_id is not None else None
        if forced is None:
            report.files_emptied.append(relative)
            forced = b""
        if os.path.getsize(live_path) > len(forced):
            report.files_truncated.append(relative)
        out_path = os.path.join(staging, relative)
        os.makedirs(os.path.dirname(out_path), mode=0o700, exist_ok=True)
        with open(out_path, "wb") as fp:
            fp.write(forced)
        os.chmod(out_path, 0o600)


def rebuild(shadow_root: str, data_root: str, mode: str, rng: random.Random) -> RebuildReport:
    if mode not in MODES:
        raise ValueError(f"unknown rebuild mode {mode}")
    report = RebuildReport(mode=mode)
    data_root = os.path.abspath(data_root)
    staging = data_root + ".rebuild"
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging, mode=0o700)
    if mode == EAGER:
        _restore_eager(shadow_root, data_root, staging, report)
    else:
        restored = set()
        _restore_folder(shadow_root, data_root, staging, "", None, mode, rng, report, restored)
        report.files_dropped = [path for path in _live_files(data_root) if path not in restored]
    shutil.rmtree(data_root)
    os.rename(staging, data_root)
    return report
