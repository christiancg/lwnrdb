import argparse
import os
import re
import statistics
import sys
import xml.etree.ElementTree as ElementTree
from typing import Dict, Iterable, List, Set

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEST_SOURCES = os.path.join("src", "test", "java")
DURATIONS_FILE = os.path.join("test_utils", "tools", "test-durations.tsv")
POM_NAMESPACE = {"pom": "http://maven.apache.org/POM/4.0.0"}
SHARDED_EXECUTION = "default-test"
SERIAL_EXECUTIONS = ("tests-that-replace-the-config-file", "tests-that-measure-allocation")
SUREFIRE_DEFAULT_INCLUDES = ("**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java")
SUREFIRE_DEFAULT_EXCLUDES = ("**/*$*",)


def ant_pattern(pattern: str) -> re.Pattern:
    expression = ""
    index = 0
    while index < len(pattern):
        if pattern.startswith("**/", index):
            expression += "(?:.*/)?"
            index += 3
        elif pattern[index] == "*":
            expression += "[^/]*"
            index += 1
        elif pattern[index] == "?":
            expression += "[^/]"
            index += 1
        else:
            expression += re.escape(pattern[index])
            index += 1
    return re.compile(expression + r"\Z")


def matches_any(path: str, patterns: Iterable[str]) -> bool:
    return any(ant_pattern(pattern).match(path) for pattern in patterns)


def surefire_patterns(pom_path: str, execution_id: str, kind: str) -> List[str]:
    root = ElementTree.parse(pom_path).getroot()
    for execution in root.iterfind(".//pom:plugin/pom:executions/pom:execution", POM_NAMESPACE):
        if execution.findtext("pom:id", namespaces=POM_NAMESPACE) == execution_id:
            return [element.text.strip() for element in
                    execution.iterfind(f"pom:configuration/pom:{kind}s/pom:{kind}", POM_NAMESPACE)]
    return []


def source_files(root: str) -> List[str]:
    base = os.path.join(root, TEST_SOURCES)
    found = []
    for folder, _, files in os.walk(base):
        for name in files:
            if name.endswith(".java"):
                found.append(os.path.relpath(os.path.join(folder, name), base).replace(os.sep, "/"))
    return sorted(found)


def sharded_classes(root: str) -> List[str]:
    excluded = surefire_patterns(os.path.join(root, "pom.xml"), SHARDED_EXECUTION, "exclude")
    return [path for path in source_files(root)
            if matches_any(path, SUREFIRE_DEFAULT_INCLUDES)
            and not matches_any(path, SUREFIRE_DEFAULT_EXCLUDES + tuple(excluded))]


def serial_classes(root: str) -> List[str]:
    included = [pattern for execution in SERIAL_EXECUTIONS
                for pattern in surefire_patterns(os.path.join(root, "pom.xml"), execution, "include")]
    return [path for path in source_files(root) if matches_any(path, included)]


def read_durations(path: str) -> Dict[str, float]:
    if not os.path.isfile(path):
        return {}
    durations = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[0]:
                durations[parts[0]] = float(parts[1])
    return durations


def plan(classes: List[str], weights: Dict[str, float], shards: int) -> List[List[str]]:
    default = statistics.median(weights.values()) if weights else 1.0
    bins = [(0.0, index, []) for index in range(shards)]
    for name in sorted(classes, key=lambda each: (-weights.get(each, default), each)):
        load, index, members = min(bins)
        members.append(name)
        bins[index] = (load + weights.get(name, default), index, members)
    return [sorted(members) for _, _, members in sorted(bins, key=lambda each: each[1])]


def write_plan(root: str, shards: int, out_dir: str) -> List[List[str]]:
    shard_lists = plan(sharded_classes(root), read_durations(os.path.join(root, DURATIONS_FILE)), shards)
    os.makedirs(out_dir, exist_ok=True)
    for number, members in enumerate(shard_lists, start=1):
        with open(os.path.join(out_dir, f"shard-{number}.txt"), "w", encoding="utf-8") as handle:
            handle.write("".join(member + "\n" for member in members))
    return shard_lists


def class_path(report_name: str) -> str:
    return report_name.split("$", 1)[0].replace(".", "/") + ".java"


def report_suites(reports_dir: str) -> List[ElementTree.Element]:
    suites = []
    for folder, _, files in os.walk(reports_dir):
        for name in sorted(files):
            if name.startswith("TEST-") and name.endswith(".xml"):
                suites.append(ElementTree.parse(os.path.join(folder, name)).getroot())
    return suites


def record(reports_dir: str, durations_path: str) -> Dict[str, float]:
    totals: Dict[str, float] = {}
    for suite in report_suites(reports_dir):
        path = class_path(suite.get("name"))
        totals[path] = totals.get(path, 0.0) + float(suite.get("time", "0"))
    with open(durations_path, "w", encoding="utf-8") as handle:
        for path in sorted(totals):
            handle.write(f"{path}\t{totals[path]:.3f}\n")
    return totals


def ran_counts(reports_dir: str) -> Dict[str, int]:
    counts: Dict[str, Set[str]] = {}
    for folder, _, files in os.walk(reports_dir):
        for name in files:
            if name.startswith("TEST-") and name.endswith(".xml"):
                suite = ElementTree.parse(os.path.join(folder, name)).getroot()
                counts.setdefault(class_path(suite.get("name")), set()).add(folder)
    return {path: len(folders) for path, folders in counts.items()}


def check_complete(root: str, reports_dir: str) -> List[str]:
    existing = set(sharded_classes(root)) | set(serial_classes(root))
    known = set(read_durations(os.path.join(root, DURATIONS_FILE))) & existing
    ran = ran_counts(reports_dir)
    problems = [f"did not run: {path}" for path in sorted(known - set(ran))]
    problems += [f"ran in {count} shards: {path}" for path, count in sorted(ran.items()) if count > 1]
    return problems


def unrecorded(root: str, reports_dir: str) -> List[str]:
    known = set(read_durations(os.path.join(root, DURATIONS_FILE)))
    ran = ran_counts(reports_dir)
    return sorted(path for path in set(sharded_classes(root)) | set(serial_classes(root))
                  if path not in known and path not in ran)


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Split the unit test classes into balanced Surefire shards.")
    parser.add_argument("--root", default=REPO_ROOT)
    parser.add_argument("--shards", type=int)
    parser.add_argument("--out")
    parser.add_argument("--expected", action="store_true", help="print every class the shards must cover")
    parser.add_argument("--record", metavar="REPORTS_DIR", help="rewrite the durations from Surefire reports")
    parser.add_argument("--check-complete", metavar="REPORTS_DIR",
                        help="fail when a recorded class did not run or ran in more than one shard")
    args = parser.parse_args(argv)
    if args.record:
        record(args.record, os.path.join(args.root, DURATIONS_FILE))
        return 0
    if args.expected:
        print("\n".join(sharded_classes(args.root) + serial_classes(args.root)))
        return 0
    if args.check_complete:
        problems = check_complete(args.root, args.check_complete)
        for path in unrecorded(args.root, args.check_complete):
            print(f"not in {DURATIONS_FILE} and produced no report (a helper, or a class to record): {path}")
        for problem in problems:
            print(problem)
        return 1 if problems else 0
    if args.shards is None or args.shards < 1 or not args.out:
        parser.error("--shards N (N >= 1) and --out DIR are required to write a plan")
    for number, members in enumerate(write_plan(args.root, args.shards, args.out), start=1):
        print(f"shard {number}: {len(members)} classes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
