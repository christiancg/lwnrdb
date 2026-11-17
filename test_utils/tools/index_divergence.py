import argparse
import json
import os
import pathlib
import re
import sys
from dataclasses import dataclass, field

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import base_utils as bu

SEPARATOR = bu.PK_FIELD_SEPARATOR
ADD = "+"
REMOVE = "-"
DELETED = "-"
INDEX_LOST = "index lost the id"
STALE_INDEX = "index holds another value"
NOT_ON_PAGE = "no live copy on a page"
CONSISTENT = "index and page agree"


@dataclass
class Sighting:
    page_value: object = None
    on_page: bool = False
    index_keys: list = field(default_factory=list)
    in_wal: bool = False
    malformed_log_lines: list = field(default_factory=list)
    verdict: str = ""


def pk_row(collection_folder: str, coll: str, doc_id: str):
    path = os.path.join(collection_folder, f"{coll}-pk.idx")
    if not os.path.isfile(path):
        return None
    row = None
    for fields in bu.read_pk_log(path):
        if bu.unescape_index_token(fields[0]) == doc_id:
            row = None if len(fields) == 2 and fields[1] == DELETED else fields
    return row


def page_document(collection_folder: str, coll: str, row):
    if row is None or len(row) < 4:
        return None
    position, length, page = int(row[1]), int(row[2]), row[3]
    path = os.path.join(collection_folder, f"{coll}-{page}.dat")
    if not os.path.isfile(path):
        return None
    with open(path, "rb") as fp:
        fp.seek(position)
        line = fp.read(length).decode("utf-8", errors="replace").strip()
    try:
        return json.loads(line)
    except ValueError:
        return None


def index_files(collection_folder: str, coll: str, field_name: str) -> list:
    prefix = f"{coll}-{field_name}-"
    return sorted(name for name in os.listdir(collection_folder)
                  if name.startswith(prefix) and (name.endswith(".idx") or name.endswith(".idxlog")))


def effective_keys(collection_folder: str, coll: str, field_name: str, doc_id: str) -> list:
    keys = set()
    for name in index_files(collection_folder, coll, field_name):
        with open(os.path.join(collection_folder, name), encoding="utf-8", errors="replace") as fp:
            lines = fp.read().split("\n")
        is_log = name.endswith(".idxlog")
        for line in lines:
            parts = line.rstrip("\r").split(SEPARATOR)
            if is_log and len(parts) >= 3 and parts[0] in (ADD, REMOVE):
                ids = {bu.unescape_index_token(token) for token in parts[2:]}
                if doc_id in ids:
                    key = f"{name}:{parts[1]}"
                    (keys.add if parts[0] == ADD else keys.discard)(key)
            elif not is_log and len(parts) >= 2:
                if doc_id in {bu.unescape_index_token(token) for token in parts[1:]}:
                    keys.add(f"{name}:{parts[0]}")
    return sorted(keys)


def malformed_lines_of(path: str) -> list:
    with open(path, encoding="utf-8", errors="replace") as fp:
        lines = fp.read().split("\n")
    name = os.path.basename(path)
    return [f"{name}:{line.replace(SEPARATOR, '|')}" for line in lines[:-1] if not is_log_line(line)]


def is_log_line(line: str) -> bool:
    parts = line.rstrip("\r").split(SEPARATOR)
    return len(parts) >= 3 and parts[0] in (ADD, REMOVE)


def malformed_log_lines(collection_folder: str, coll: str, field_name: str) -> list:
    return [line for name in index_files(collection_folder, coll, field_name) if name.endswith(".idxlog")
            for line in malformed_lines_of(os.path.join(collection_folder, name))]


def malformed_logs_under(db_root: str) -> list:
    return [line for folder, _, names in sorted(os.walk(db_root)) for name in sorted(names)
            if name.endswith(".idxlog") for line in malformed_lines_of(os.path.join(folder, name))]


def appears_in_wal(collection_folder: str, coll: str, doc_id: str) -> bool:
    path = os.path.join(collection_folder, f"{coll}.wal")
    if not os.path.isfile(path):
        return False
    with open(path, "rb") as fp:
        return id_pattern(doc_id).search(fp.read()) is not None


def id_pattern(doc_id: str):
    return re.compile(rb"(?<![A-Za-z0-9_-])" + re.escape(doc_id.encode("utf-8")) + rb"(?![A-Za-z0-9_-])")


def verdict_of(sighting: Sighting) -> str:
    if not sighting.on_page:
        return NOT_ON_PAGE
    if not sighting.index_keys:
        return INDEX_LOST
    value = str(sighting.page_value)
    if any(key.rsplit(":", 1)[1] in (value, value.rstrip("0").rstrip(".")) for key in sighting.index_keys):
        return CONSISTENT
    return STALE_INDEX


def sight(db_root: str, database: str, coll: str, field_name: str, doc_id: str) -> Sighting:
    folder = os.path.join(db_root, database, coll)
    sighting = Sighting()
    if not os.path.isdir(folder):
        sighting.verdict = NOT_ON_PAGE
        return sighting
    document = page_document(folder, coll, pk_row(folder, coll, doc_id))
    sighting.on_page = document is not None
    sighting.page_value = document.get(field_name) if document else None
    sighting.index_keys = effective_keys(folder, coll, field_name, doc_id)
    sighting.in_wal = appears_in_wal(folder, coll, doc_id)
    sighting.malformed_log_lines = malformed_log_lines(folder, coll, field_name)
    sighting.verdict = verdict_of(sighting)
    return sighting


def classify(points: dict, database: str, coll: str, field_name: str, ids: list) -> list:
    rows = []
    for doc_id in ids:
        row = {"id": doc_id}
        for label, db_root in points.items():
            row[label] = vars(sight(db_root, database, coll, field_name, doc_id))
        rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="classify the ids an O3 divergence names, from kept db folders")
    parser.add_argument("--at-power-loss", required=True, help="the db folder as the power loss left it")
    parser.add_argument("--after-recovery", default="", help="the db folder after the first restart")
    parser.add_argument("--database", default="durability_db")
    parser.add_argument("--collection", required=True)
    parser.add_argument("--field", required=True)
    parser.add_argument("--ids", required=True, help="comma-separated ids")
    args = parser.parse_args()
    points = {"at_power_loss": args.at_power_loss}
    if args.after_recovery:
        points["after_recovery"] = args.after_recovery
    rows = classify(points, args.database, args.collection, args.field, [i for i in args.ids.split(",") if i])
    for row in rows:
        stages = "; ".join(f"{label}: {row[label]['verdict']} (page {row[label]['page_value']!r},"
                           f" index {row[label]['index_keys']}, in wal {row[label]['in_wal']},"
                           f" malformed log lines {row[label]['malformed_log_lines']})"
                           for label in points)
        print(f"{row['id']}: {stages}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
