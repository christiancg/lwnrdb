#!/usr/bin/env python3
"""Filtered test262 conformance harness for SimpleJS.

Runs the official tc39/test262 corpus against SimpleJs.run(source, HostBindings), filtered down to
the language + built-ins surface a database script host actually exposes, and gates on a tracked
baseline of known failures so conformance can only ratchet upward.

Stdlib only: CI has no `pip install` step, so there is no PyYAML and no requests. That constraint is
why the frontmatter parser below is hand-rolled.

    python3 test_utils/test262.py --fetch              # download + verify + extract the corpus
    python3 test_utils/test262.py --self-test          # check the harness itself (no corpus needed)
    python3 test_utils/test262.py --gate baseline      # the CI run
    python3 test_utils/test262.py --update-baseline    # after fixing a gap
    python3 test_utils/test262.py --self-check         # assert the known divergences still fail
    python3 test_utils/test262.py --dump-failures      # re-run the baseline and write the failure inventory
"""

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG_DIR = ROOT / "config"
PROPERTIES = CONFIG_DIR / "test262.properties"
EXCLUSIONS = CONFIG_DIR / "test262-exclusions.txt"
BASELINE = CONFIG_DIR / "test262-baseline.txt"
FEATURES = CONFIG_DIR / "test262-features.txt"
CORPUS = ROOT / "test262"
SHIMS = ROOT / "test_utils" / "test262_shims"
FIXTURES = ROOT / "test_utils" / "test262_fixtures"
REPORT = ROOT / "test_log" / "test262-report.md"
FAILURES = ROOT / "test_log" / "test262-failures.tsv"
CLASSPATH = os.pathsep.join([str(ROOT / "target" / "test-classes"), str(ROOT / "target" / "classes")])
WORKER_CLASS = "org.techhouse.unit.simplejs.test262.Test262Worker"

DEFAULT_TIMEOUT = 10.0
BATCH_SIZE = 40
MAX_CONSECUTIVE_CRASHES = 5

PASS_COLOUR = "\033[92m"
FAIL_COLOUR = "\033[91m"
WARN_COLOUR = "\033[93m"
RESET = "\033[0m"

# Flags that say nothing about how this runner should execute a test.
IGNORED_FLAGS = {"generated", "CanBlockIsFalse", "CanBlockIsTrue", "non-deterministic"}

# The confirmed divergences the first run has to reproduce. A row that comes back all-green means the
# filter, the prelude or the verdict logic is wrong — not that the engine improved.
DIVERGENCES = []
# A row is deleted in the same commit that closes its divergence, naming the test id that now passes.
# When the last row goes, self_check inverts and asserts the baseline is empty instead: at that point
# "all green" is the expected state, not the alarm - which is where the engine now stands.
# Rows are removed from the list above as their divergence is closed. A row only asserts that *some*
# test under its prefix still fails, so a stale one keeps passing on an unrelated failure and quietly
# becomes a false claim about the engine - the eleven dropped so far (String(symbol) throwing,
# Reflect.ownKeys omitting symbols, shallow primitive wrappers, typed arrays lacking the
# integer-indexed internals, regex named-group limitations, Object.assign bypassing a setter,
# instanceof surviving F.prototype reassignment - closed by primitive-prototype-with-primitive.js
# passing, tagged template strings now cached per call site - closed by
# cache-same-site.js/cache-different-functions-same-site.js/cache-same-site-top-level.js passing,
# descriptor coercion gaps (ToPropertyKey, ToPropertyDescriptor) - the row's own proving test,
# built-ins/Object/defineProperty/15.2.3.6-4-243-2.js, turned out to be a plain strict-mode
# write-through-a-getter-only-array-index bug unrelated to descriptor coercion at all, fixed in
# MemberEvaluator.setArrayMember, Function.prototype.toString retaining no source - closed by
# built-ins/Function/prototype/toString/method-computed-property-name.js passing once the parser
# records a source span for every function-like production, and finally the java.util.regex
# capture-reset/lookbehind-backreference/code-unit-stepping family - closed by replacing
# java.util.regex entirely with a purpose-built backtracking matcher (internal/regex/), which
# implements ECMA-262 Pattern Semantics directly: RegexParser compiles a Pattern straight to an
# RxNode AST (internal/regex/RxNode.java) and RegexMatcher executes it via continuation-passing
# backtracking with real per-iteration capture snapshot/restore, bidirectional (forward/backward)
# matching for unbounded lookbehind, and code-unit- vs code-point-aware stepping keyed off the u/v
# flag) were each verified fixed by probe first.


class HarnessError(RuntimeError):
    """A harness-level problem: bad config, bad corpus, an unparseable test."""


# ---------------------------------------------------------------------------
# Phase 1 — corpus acquisition
# ---------------------------------------------------------------------------


def load_properties():
    if not PROPERTIES.is_file():
        raise HarnessError(f"missing {PROPERTIES.relative_to(ROOT)}")
    props = {}
    for line in PROPERTIES.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    for required in ("test262.commit", "test262.url", "test262.sha256"):
        if not props.get(required):
            raise HarnessError(f"{PROPERTIES.relative_to(ROOT)} is missing {required}")
    return props


def corpus_stamp():
    stamp = CORPUS / ".commit"
    return stamp.read_text(encoding="utf-8").strip() if stamp.is_file() else ""


def fetch(props):
    commit = props["test262.commit"]
    if corpus_stamp() == commit and (CORPUS / "test").is_dir() and (CORPUS / "harness").is_dir():
        print(f"corpus already at {commit[:12]} — nothing to fetch")
        return
    print(f"fetching tc39/test262 {commit[:12]} from {props['test262.url']}")
    with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tarball:
        digest = hashlib.sha256()
        with urllib.request.urlopen(props["test262.url"], timeout=300) as response:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                digest.update(chunk)
                tarball.write(chunk)
        archive = Path(tarball.name)
    try:
        actual = digest.hexdigest()
        if actual != props["test262.sha256"]:
            raise HarnessError(
                "tarball sha256 mismatch: "
                f"expected {props['test262.sha256']}, got {actual}. Refusing to extract."
            )
        if CORPUS.exists():
            shutil.rmtree(CORPUS)
        CORPUS.mkdir(parents=True)
        extract(archive)
    finally:
        archive.unlink(missing_ok=True)
    (CORPUS / ".commit").write_text(commit + "\n", encoding="utf-8")
    tests = sum(1 for _ in (CORPUS / "test").rglob("*.js"))
    print(f"extracted {tests} test files + {len(list((CORPUS / 'harness').glob('*.js')))} harness files")


def extract(archive):
    """Extract only test/ and harness/, stripping the archive's top-level directory."""
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            if not member.isfile():
                continue
            parts = Path(member.name).parts
            if len(parts) < 3 or parts[1] not in ("test", "harness"):
                continue
            target = CORPUS.joinpath(*parts[1:])
            if not str(target.resolve()).startswith(str(CORPUS.resolve())):
                raise HarnessError(f"refusing to extract outside the corpus: {member.name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            source = tar.extractfile(member)
            if source is not None:
                target.write_bytes(source.read())


# ---------------------------------------------------------------------------
# Phase 2 — frontmatter parsing and the filter
# ---------------------------------------------------------------------------

FRONTMATTER = re.compile(r"/\*---(.*?)---\*/", re.DOTALL)
INLINE_LIST = re.compile(r"^\[(.*)\]$")


LIST_KEYS = ("includes", "features", "flags")
BLOCK_SCALARS = ("|", ">", "|-", ">-", "|+", ">+")


def parse_frontmatter(source, test_id):
    """Parse the subset of the YAML frontmatter test262 actually uses.

    Recognised: negative (phase/type), includes (inline and block-list forms), flags, features,
    locale. Everything else (description, esid, info, author) is ignored, including `info: |` block
    scalars, whose free text must not be mistaken for mapping keys. An unparseable line is a hard
    error rather than a flag-less test, because a silent mis-parse would inflate the pass rate.
    """
    meta = {"flags": [], "includes": [], "features": [], "negative": None}
    match = FRONTMATTER.search(source)
    if not match:
        return meta
    lines = [line.rstrip() for line in match.group(1).splitlines()]
    content = [line for line in lines if line.strip() and not line.strip().startswith("#")]
    if not content:
        return meta
    base = min(len(line) - len(line.lstrip()) for line in content)
    key = None
    negative = None
    index = 0
    while index < len(lines):
        line = lines[index]
        index += 1
        if not line.strip() or line.strip().startswith("#"):
            continue
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())
        if indent > base:
            if stripped.startswith("- ") and key in LIST_KEYS:
                meta[key].append(unquote(stripped[2:]))
            elif key == "negative" and ":" in stripped:
                name, _, value = stripped.partition(":")
                negative[name.strip()] = unquote(value)
            continue
        if ":" not in stripped:
            raise HarnessError(f"{test_id}: unparseable frontmatter line {line!r}")
        name, _, value = stripped.partition(":")
        key = name.strip()
        value = value.strip()
        if value in BLOCK_SCALARS:
            # Free text: skip every following more-indented line so it cannot be read as a key.
            while index < len(lines) and (
                not lines[index].strip() or len(lines[index]) - len(lines[index].lstrip()) > base
            ):
                index += 1
            key = None
            continue
        if key in LIST_KEYS:
            meta[key] = parse_list(value)
        elif key == "negative":
            negative = {}
    if negative is not None:
        if not negative:
            raise HarnessError(f"{test_id}: a negative frontmatter block was found but not parsed")
        meta["negative"] = negative
    return meta


def unquote(value):
    return value.strip().strip("'\"")


def parse_list(value):
    if not value:
        return []
    inline = INLINE_LIST.match(value)
    if inline:
        return [item.strip().strip("'\"") for item in inline.group(1).split(",") if item.strip()]
    return [value.strip("'\"")]


def load_exclusions():
    if not EXCLUSIONS.is_file():
        raise HarnessError(f"missing {EXCLUSIONS.relative_to(ROOT)}")
    dirs, keeps, features, patterns, includes = [], [], {}, [], {}
    for number, raw in enumerate(EXCLUSIONS.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        kind, _, rest = line.partition(":")
        kind = kind.strip()
        value, _, reason = rest.partition("#")
        value, reason = value.strip(), reason.strip()
        if not reason:
            raise HarnessError(f"{EXCLUSIONS.name}:{number}: every exclusion needs a trailing '# reason'")
        if kind == "dir":
            dirs.append((value, reason))
        elif kind == "keep":
            keeps.append((value, reason))
        elif kind == "feature":
            features[value] = reason
        elif kind == "include":
            includes[value] = reason
        elif kind == "pattern":
            patterns.append((re.compile(value), value, reason))
        else:
            raise HarnessError(f"{EXCLUSIONS.name}:{number}: unknown exclusion kind {kind!r}")
    return {"dirs": dirs, "keeps": keeps, "features": features, "patterns": patterns, "includes": includes}


def classify(test_id, source, meta, exclusions):
    """Decide how (or whether) to run one test. Returns (verdict, detail).

    verdict is one of RUN / SKIP / EXCLUDED; detail is the reason for the latter two.
    """
    if not any(test_id.startswith(prefix) for prefix, _ in exclusions["keeps"]):
        for prefix, reason in exclusions["dirs"]:
            if test_id.startswith(prefix):
                return "EXCLUDED", reason
    flags = set(meta["flags"])
    if "module" in flags:
        return "EXCLUDED", "module resolution is limited to the args/db host built-ins"
    if "noStrict" in flags:
        return "SKIP", "sloppy-only semantics the engine deliberately lacks"
    for feature in meta["features"]:
        if feature in exclusions["features"]:
            return "EXCLUDED", exclusions["features"][feature]
    for include in meta["includes"]:
        if include in exclusions["includes"]:
            return "EXCLUDED", exclusions["includes"][include]
    for compiled, _, reason in exclusions["patterns"]:
        if compiled.search(source):
            return "EXCLUDED", reason
    return "RUN", ""


def known_features(exclusions):
    """Features this harness has already seen: the excluded ones plus the acknowledged inventory.

    The inventory is regenerated with the baseline, so after a corpus bump the report lists exactly
    the features the corpus has newly introduced instead of every feature in the suite.
    """
    known = set(exclusions["features"])
    if FEATURES.is_file():
        for raw in FEATURES.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line and not line.startswith("#"):
                known.add(line)
    return known


def write_features(collected, commit):
    seen = set(collected["unknown_features"])
    if FEATURES.is_file():
        for raw in FEATURES.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line and not line.startswith("#"):
                seen.add(line)
    lines = [
        "# Features present in the pinned corpus and already accounted for. Regenerated together with",
        "# the baseline: python3 test_utils/test262.py --update-baseline. A feature that appears here",
        "# is measured (pass or fail); one that is deliberately not measured belongs in",
        "# config/test262-exclusions.txt instead. Anything missing from both is reported as an unknown",
        "# feature, so a corpus bump surfaces newly-added areas.",
        f"# corpus: {commit}",
    ]
    lines.extend(sorted(seen))
    FEATURES.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {FEATURES.relative_to(ROOT)} with {len(seen)} acknowledged features")


# ---------------------------------------------------------------------------
# Test collection
# ---------------------------------------------------------------------------


def collect(test_root, harness_dir, exclusions, name_filter, known):
    """Walk the corpus and split it into runnable jobs, skips and exclusions."""
    jobs, skipped, excluded, unknown_features, undecodable = [], [], [], {}, []
    for path in sorted(test_root.rglob("*.js")):
        test_id = path.relative_to(test_root).as_posix()
        if test_id.endswith("_FIXTURE.js"):
            continue
        if name_filter and name_filter not in test_id:
            continue
        raw = path.read_bytes()
        try:
            source = raw.decode("utf-8")
        except UnicodeDecodeError:
            # Only a genuinely malformed file counts: a U+FFFD the corpus committed on purpose is
            # ordinary content, not a decoding failure, and flagging it buried the real signal.
            source = raw.decode("utf-8", errors="replace")
            undecodable.append(test_id)
        meta = parse_frontmatter(source, test_id)
        for feature in meta["features"]:
            if feature not in known:
                unknown_features.setdefault(feature, 0)
                unknown_features[feature] += 1
        verdict, reason = classify(test_id, source, meta, exclusions)
        if verdict == "SKIP":
            skipped.append((test_id, reason))
            continue
        if verdict == "EXCLUDED":
            excluded.append((test_id, reason))
            continue
        negative = meta["negative"] or {}
        jobs.append(
            {
                "id": test_id,
                "path": str(path),
                "includes": meta["includes"],
                "flags": sorted(set(meta["flags"]) - IGNORED_FLAGS),
                "negativeType": negative.get("type", ""),
                "negativePhase": negative.get("phase", ""),
                "harnessDir": str(harness_dir),
                "shimDir": str(SHIMS),
            }
        )
    return {
        "jobs": jobs,
        "skipped": skipped,
        "excluded": excluded,
        "unknown_features": unknown_features,
        "undecodable": undecodable,
    }


# ---------------------------------------------------------------------------
# Phase 4 — the worker pool
# ---------------------------------------------------------------------------

LIVE_WORKERS = set()


class WorkerDied(RuntimeError):
    pass


class Worker:
    """One worker JVM, driven one job at a time so a per-test timeout is meaningful."""

    def __init__(self, command):
        self.command = command
        self.errors = tempfile.TemporaryFile()
        self.process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.errors,
            cwd=str(ROOT),
        )
        self.buffer = b""
        LIVE_WORKERS.add(self)

    def send(self, job):
        try:
            self.process.stdin.write((json.dumps(job) + "\n").encode("utf-8"))
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as broken:
            raise WorkerDied(str(broken)) from broken

    def read_line(self, timeout):
        """Read one result line, or return None if the worker went quiet for `timeout` seconds."""
        deadline = time.monotonic() + timeout
        while b"\n" not in self.buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            if not self.wait_readable(remaining):
                continue
            chunk = os.read(self.process.stdout.fileno(), 1 << 16)
            if not chunk:
                raise WorkerDied(self.stderr_tail())
            self.buffer += chunk
        line, _, self.buffer = self.buffer.partition(b"\n")
        return line.decode("utf-8", errors="replace")

    def wait_readable(self, timeout):
        import select

        readable, _, _ = select.select([self.process.stdout.fileno()], [], [], timeout)
        return bool(readable)

    def stderr_tail(self):
        try:
            self.errors.seek(0)
            text = self.errors.read().decode("utf-8", errors="replace").strip()
        except OSError:
            return ""
        return text.splitlines()[-1] if text else ""

    def kill(self):
        LIVE_WORKERS.discard(self)
        for stream in (self.process.stdin, self.process.stdout):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass
        if self.process.poll() is None:
            self.process.kill()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        self.errors.close()

    def close(self):
        LIVE_WORKERS.discard(self)
        try:
            if self.process.stdin:
                self.process.stdin.close()
            self.process.wait(timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
        finally:
            self.errors.close()


def worker_command():
    override = os.environ.get("TEST262_WORKER_CMD")
    if override:
        return override.split()
    return ["java", "-XX:+UseSerialGC", "-Xss8m", "-cp", CLASSPATH, WORKER_CLASS]


def run_batch(jobs, timeout, command, max_consecutive_crashes=MAX_CONSECUTIVE_CRASHES):
    """Run one batch on a reused worker, respawning it whenever a test hangs or crashes it.

    Only a dead worker counts toward the crash-loop abort: a timeout is a result about the engine,
    and a batch made mostly of known hangs (a filtered or baseline-only run) is not a broken
    classpath.
    """
    results = {}
    worker = Worker(command)
    consecutive_crashes = 0
    try:
        for job in jobs:
            outcome = None
            note = ""
            crashed = False
            try:
                worker.send(job)
                line = worker.read_line(timeout)
                if line is None:
                    note = f"no result within {timeout:.0f}s"
                else:
                    outcome = json.loads(line)
            except (WorkerDied, json.JSONDecodeError) as died:
                crashed = isinstance(died, WorkerDied)
                note = f"crash: {died}" if crashed else f"unparseable result: {died}"
            if outcome is None:
                results[job["id"]] = {"status": "HANG", "errorName": "", "message": note}
                worker.kill()
                worker = Worker(command)
                consecutive_crashes = consecutive_crashes + 1 if crashed else 0
                if consecutive_crashes >= max_consecutive_crashes:
                    raise HarnessError(
                        f"{consecutive_crashes} workers died in a row (last: {job['id']}, {note}). "
                        "Aborting instead of spinning — check the classpath with `mvn test-compile`."
                    )
                continue
            consecutive_crashes = 0
            results[outcome["id"]] = {
                "status": outcome["status"],
                "errorName": outcome.get("errorName", ""),
                "message": base64.b64decode(outcome.get("messageB64", "")).decode("utf-8", errors="replace"),
            }
    finally:
        worker.close()
    return results


def run_jobs(jobs, timeout, parallelism, max_consecutive_crashes=MAX_CONSECUTIVE_CRASHES):
    from concurrent.futures import ThreadPoolExecutor

    command = worker_command()
    batches = [jobs[i : i + BATCH_SIZE] for i in range(0, len(jobs), BATCH_SIZE)]
    results = {}
    done = 0
    started = time.monotonic()
    try:
        with ThreadPoolExecutor(max_workers=parallelism) as pool:
            for batch in pool.map(
                lambda group: run_batch(group, timeout, command, max_consecutive_crashes), batches
            ):
                results.update(batch)
                done += len(batch)
                elapsed = time.monotonic() - started
                print(
                    f"\r  {done}/{len(jobs)} tests  ({elapsed:.0f}s)",
                    end="",
                    flush=True,
                )
    finally:
        reap_workers()
    if jobs:
        print()
    return results


def reap_workers():
    for worker in list(LIVE_WORKERS):
        worker.kill()


# ---------------------------------------------------------------------------
# Phase 5 — baseline, gate, report
# ---------------------------------------------------------------------------


def load_baseline():
    if not BASELINE.is_file():
        return {}, ""
    entries, corpus = {}, ""
    for raw in BASELINE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("#"):
            if line.startswith("# corpus:"):
                corpus = line.split(":", 1)[1].strip()
            continue
        if not line:
            continue
        status, _, test_id = line.partition(" ")
        entries[test_id.strip()] = status.strip()
    return entries, corpus


def write_baseline(results, commit):
    failing = sorted(
        (test_id, outcome["status"])
        for test_id, outcome in results.items()
        if outcome["status"] in ("FAIL", "HANG")
    )
    lines = [
        "# test262 baseline — regenerate with: python3 test_utils/test262.py --update-baseline",
        f"# corpus: {commit}",
        f"# generated: {datetime.now(timezone.utc).date().isoformat()}",
        f"# entries: {len(failing)}",
    ]
    lines.extend(f"{status:<5} {test_id}" for test_id, status in failing)
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {BASELINE.relative_to(ROOT)} with {len(failing)} known failures")


def gate(results, filtered, commit):
    """Diff the run against the baseline. Returns (ok, list of gate messages)."""
    baseline, baseline_corpus = load_baseline()
    messages = []
    if not BASELINE.is_file():
        return False, ["no baseline yet — create one with --update-baseline"]
    if baseline_corpus != commit:
        return False, [
            f"baseline was generated against a different corpus ({baseline_corpus or 'unknown'} "
            f"!= {commit}); refresh it with --update-baseline"
        ]
    regressions = sorted(
        test_id
        for test_id, outcome in results.items()
        if outcome["status"] in ("FAIL", "HANG") and test_id not in baseline
    )
    fixed = sorted(
        test_id
        for test_id, outcome in results.items()
        if outcome["status"] == "PASS" and test_id in baseline
    )
    now_filtered = sorted(test_id for test_id in baseline if test_id in filtered)
    for test_id in regressions:
        messages.append(f"REGRESSION  {results[test_id]['status']}  {test_id}: {results[test_id]['message'][:160]}")
    for test_id in fixed:
        messages.append(f"STALE       now passes, drop it from the baseline: {test_id}")
    for test_id in now_filtered:
        messages.append(f"FILTERED    baselined test is now {filtered[test_id]}, refresh the baseline: {test_id}")
    return not messages, messages


def area_of(test_id):
    parts = test_id.split("/")
    return "/".join(parts[:2]) if len(parts) > 2 else parts[0]


def build_report(results, collected, commit, elapsed, gate_messages):
    counts = {}
    for test_id, outcome in results.items():
        area = counts.setdefault(area_of(test_id), {"PASS": 0, "FAIL": 0, "HANG": 0})
        area[outcome["status"]] = area.get(outcome["status"], 0) + 1
    total = {"PASS": 0, "FAIL": 0, "HANG": 0}
    for area in counts.values():
        for status in total:
            total[status] += area[status]
    denominator = sum(total.values())
    lines = [
        "# test262 conformance report",
        "",
        f"- corpus: `{commit}`",
        f"- generated: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        f"- runtime: {elapsed:.0f}s",
        "",
        "Rate is computed over PASS + FAIL + HANG. Excluded and skipped tests are reported but never",
        "counted in the denominator, so a deliberate omission cannot flatter the number. A `negative`",
        "test with `phase: parse` cannot be distinguished from `phase: resolution`: both are measured",
        "as \"SyntaxError expected\".",
        "",
        f"## Totals — {rate(total['PASS'], denominator)} ({total['PASS']}/{denominator})",
        "",
        "| Status | Count |",
        "|---|---|",
        f"| PASS | {total['PASS']} |",
        f"| FAIL | {total['FAIL']} |",
        f"| HANG | {total['HANG']} |",
        f"| SKIP (not measured) | {len(collected['skipped'])} |",
        f"| EXCLUDED (not measured) | {len(collected['excluded'])} |",
        "",
        "## Per area",
        "",
        "| Area | pass | total | rate |",
        "|---|---|---|---|",
    ]
    for area in sorted(counts):
        stats = counts[area]
        area_total = stats["PASS"] + stats["FAIL"] + stats["HANG"]
        lines.append(f"| `{area}` | {stats['PASS']} | {area_total} | {rate(stats['PASS'], area_total)} |")
    lines.extend(["", "## Not measured", "", "| Reason | Kind | Count |", "|---|---|---|"])
    lines.extend(reason_rows("EXCLUDED", collected["excluded"]))
    lines.extend(reason_rows("SKIP", collected["skipped"]))
    hangs = sorted(test_id for test_id, outcome in results.items() if outcome["status"] == "HANG")
    lines.extend(["", "## Robustness — hangs", ""])
    if hangs:
        lines.append("A hang in production is a pinned connection thread, not a wrong answer, so these")
        lines.append("are tracked separately from failures:")
        lines.append("")
        lines.extend(f"- `{test_id}` — {results[test_id]['message']}" for test_id in hangs)
    else:
        lines.append("None.")
    if collected["unknown_features"]:
        lines.extend([
            "",
            "## Unknown features",
            "",
            "Features that are in neither `config/test262-exclusions.txt` nor",
            "`config/test262-features.txt` — a corpus bump introduced them, so decide whether they are",
            "measured or excluded and refresh the inventory:",
            "",
        ])
        lines.extend(
            f"- `{feature}` ({count} tests)"
            for feature, count in sorted(collected["unknown_features"].items(), key=lambda item: -item[1])
        )
    if collected["undecodable"]:
        lines.extend(["", f"## Undecodable sources: {len(collected['undecodable'])}", ""])
        lines.extend(f"- `{test_id}`" for test_id in collected["undecodable"][:20])
    if gate_messages:
        lines.extend(["", "## Gate", ""])
        lines.extend(f"- {message}" for message in gate_messages)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return "\n".join(lines)


def reason_rows(kind, entries):
    grouped = {}
    for _, reason in entries:
        grouped[reason] = grouped.get(reason, 0) + 1
    return [
        f"| {reason} | {kind} | {count} |"
        for reason, count in sorted(grouped.items(), key=lambda item: -item[1])
    ]


def rate(passed, total):
    return "n/a" if total == 0 else f"{100.0 * passed / total:.2f}%"


def self_check(results):
    """Assert the harness is still measuring what it claims to measure.

    While DIVERGENCES is non-empty, every row must still show up as a FAIL/HANG somewhere in its
    area — an all-green row means the filter, the prelude or the verdict logic broke, not that the
    engine improved. Once the last row is deleted (a row goes when its divergence closes), the check
    inverts: the expected state is an empty baseline, so a non-empty one is the alarm.
    """
    if not DIVERGENCES:
        return self_check_empty_baseline()
    failures = {
        test_id for test_id, outcome in results.items() if outcome["status"] in ("FAIL", "HANG")
    }
    all_green = []
    print("\nself-check — the confirmed divergences must still fail:")
    for label, prefixes in DIVERGENCES:
        hit = next((test_id for test_id in failures if any(test_id.startswith(p) for p in prefixes)), None)
        if hit:
            print(f"  [{PASS_COLOUR}fails{RESET}] {label}  ({hit})")
        else:
            print(f"  [{FAIL_COLOUR}GREEN{RESET}] {label}  — no failure under {prefixes}")
            all_green.append(label)
    if all_green:
        print(
            f"\n{FAIL_COLOUR}{len(all_green)} divergence row(s) came back all-green.{RESET} "
            "A near-100% run means the harness is broken: check the prelude and the verdict logic."
        )
    return not all_green


def self_check_empty_baseline():
    """The end state: no divergences left, so the baseline itself must be empty."""
    entries, _ = load_baseline()
    print("\nself-check — no divergences left, so the baseline must be empty:")
    if entries:
        print(f"  [{FAIL_COLOUR}{len(entries)} entries{RESET}] {BASELINE.relative_to(ROOT)}")
        print(
            f"\n{FAIL_COLOUR}the baseline still lists {len(entries)} known failure(s).{RESET} "
            "Either a divergence row was deleted before its area was green, or the gate regressed."
        )
        return False
    print(f"  [{PASS_COLOUR}empty{RESET}] {BASELINE.relative_to(ROOT)}")
    return True


def dump_failures(jobs, results):
    """Write the failure inventory every later phase mines for its next target."""
    rows = sorted(
        (test_id, outcome)
        for test_id, outcome in results.items()
        if outcome["status"] in ("FAIL", "HANG")
    )
    FAILURES.parent.mkdir(parents=True, exist_ok=True)
    lines = ["\t".join(("status", "id", "errorName", "message"))]
    lines.extend(
        "\t".join(
            (
                outcome["status"],
                test_id,
                outcome["errorName"],
                " ".join(outcome["message"].split()),
            )
        )
        for test_id, outcome in rows
    )
    FAILURES.write_text("\n".join(lines) + "\n", encoding="utf-8")
    passing = sorted(test_id for test_id, outcome in results.items() if outcome["status"] == "PASS")
    missing = sorted(job["id"] for job in jobs if job["id"] not in results)
    print(f"wrote {FAILURES.relative_to(ROOT)} with {len(rows)} failing entries")
    if passing:
        print(f"  {len(passing)} baselined test(s) now pass — drop them with --update-baseline")
    if missing:
        print(f"  {WARN_COLOUR}{len(missing)} baselined test(s) produced no result{RESET}")


# ---------------------------------------------------------------------------
# Self-test: the harness measuring itself
# ---------------------------------------------------------------------------

FIXTURE_EXPECTATIONS = {
    "pass-simple.js": "PASS",
    "includes-inline.js": "PASS",
    "includes-block.js": "PASS",
    "only-strict.js": "PASS",
    "async-done.js": "PASS",
    "negative-runtime.js": "PASS",
    "negative-parse.js": "PASS",
    "raw-no-prelude.js": "PASS",
    "fail-assertion.js": "FAIL",
    "negative-mismatch.js": "FAIL",
    "async-done-message.js": "FAIL",
    "message-round-trip.js": "FAIL",
    "infinite-loop.js": "HANG",
    "no-strict.js": "SKIP",
    "module-flag.js": "EXCLUDED",
    "feature-excluded.js": "EXCLUDED",
    "pattern-excluded.js": "EXCLUDED",
}

STUB_WORKER = """
import json, sys, time
for line in sys.stdin:
    job = json.loads(line)
    if "hang" in job["id"]:
        time.sleep(600)
    if "crash" in job["id"]:
        sys.exit(3)
    print(json.dumps({"id": job["id"], "status": "PASS", "errorName": "", "messageB64": ""}), flush=True)
"""


def self_test():
    ok = True
    print("self-test — fixtures through the real worker")
    exclusions = load_exclusions()
    collected = collect(FIXTURES / "test", FIXTURES / "harness", exclusions, None, known_features(exclusions))
    # The driver timeout is deliberately below the worker's 5s wall clock so the infinite-loop fixture
    # exercises the kill-and-respawn path rather than the engine's own timeout.
    results = run_jobs(collected["jobs"], 2.0, 2)
    actual = {test_id: outcome["status"] for test_id, outcome in results.items()}
    for test_id, reason in collected["skipped"]:
        actual[test_id] = "SKIP"
    for test_id, reason in collected["excluded"]:
        actual[test_id] = "EXCLUDED"
    for name, expected in sorted(FIXTURE_EXPECTATIONS.items()):
        got = actual.get(name, "MISSING")
        if got == expected:
            print(f"  [{PASS_COLOUR}ok{RESET}] {name}: {got}")
        else:
            ok = False
            detail = results.get(name, {}).get("message", "")
            print(f"  [{FAIL_COLOUR}FAIL{RESET}] {name}: expected {expected}, got {got}  {detail[:120]}")
    unexpected = sorted(set(actual) - set(FIXTURE_EXPECTATIONS))
    if unexpected:
        ok = False
        print(f"  [{FAIL_COLOUR}FAIL{RESET}] unexpected fixtures: {unexpected}")
    round_trip = results.get("message-round-trip.js", {}).get("message", "")
    ok = report_check(
        "error message survives base64 round trip",
        "\n" in round_trip and '"' in round_trip,
        repr(round_trip),
    ) and ok
    ok = self_test_frontmatter() and ok
    ok = self_test_classify() and ok
    ok = self_test_robustness() and ok
    ok = self_test_gate() and ok
    print(f"\nself-test: {'all checks passed' if ok else 'FAILURES above'}")
    return ok


def self_test_classify():
    print("self-test — classification")
    empty_meta = {"flags": [], "features": [], "includes": [], "negative": {}}
    rules = {
        "dirs": [("area/skipme/", "excluded subtree")],
        "keeps": [("area/skipme/inner/", "measured anyway")],
        "features": {"SharedArrayBuffer": "deliberate"},
        "includes": {"needsCodegen.js": "helper needs the Function constructor"},
        "patterns": [],
    }
    checks = [
        ("dir excludes", "area/skipme/a.js", "", empty_meta, "EXCLUDED"),
        ("keep beats dir", "area/skipme/inner/a.js", "", empty_meta, "RUN"),
        ("dir leaves siblings alone", "area/other/a.js", "", empty_meta, "RUN"),
        ("feature still applies under keep", "area/skipme/inner/b.js", "",
         {"flags": [], "features": ["SharedArrayBuffer"], "includes": [], "negative": {}}, "EXCLUDED"),
        ("include excludes", "area/other/c.js", "",
         {"flags": [], "features": [], "includes": ["needsCodegen.js"], "negative": {}}, "EXCLUDED"),
        ("unlisted include runs", "area/other/d.js", "",
         {"flags": [], "features": [], "includes": ["assert.js"], "negative": {}}, "RUN"),
    ]
    ok = True
    for label, test_id, source, meta, expected in checks:
        got = classify(test_id, source, meta, rules)[0]
        ok = report_check(label, got == expected, f"{got} != {expected}") and ok
    return ok


def self_test_frontmatter():
    print("self-test — frontmatter parsing")
    checks = [
        ("inline includes", "/*---\nincludes: [compareArray.js, propertyHelper.js]\n---*/", "includes",
         ["compareArray.js", "propertyHelper.js"]),
        ("block includes", "/*---\nincludes:\n  - compareArray.js\n  - sta.js\n---*/", "includes",
         ["compareArray.js", "sta.js"]),
        ("flags", "/*---\nflags: [onlyStrict, generated]\n---*/", "flags", ["onlyStrict", "generated"]),
        ("features", "/*---\nfeatures: [Temporal]\n---*/", "features", ["Temporal"]),
        ("no frontmatter", "var x = 1;", "flags", []),
    ]
    ok = True
    for label, source, key, expected in checks:
        got = parse_frontmatter(source, label)[key]
        ok = report_check(label, got == expected, f"{got!r} != {expected!r}") and ok
    negative = parse_frontmatter("/*---\nnegative:\n  phase: parse\n  type: SyntaxError\n---*/", "neg")["negative"]
    ok = report_check("negative block", negative == {"phase": "parse", "type": "SyntaxError"}, str(negative)) and ok
    return ok


def self_test_robustness():
    print("self-test — driver robustness (stub worker)")
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as stub:
        stub.write(STUB_WORKER)
        stub_path = stub.name
    command = [sys.executable, stub_path]
    try:
        jobs = [job_stub("ok-one"), job_stub("hang-me"), job_stub("ok-two"), job_stub("crash-me")]
        results = run_batch(jobs, 1.0, command)
        ok = report_check("clean job passes", results["ok-one"]["status"] == "PASS", str(results.get("ok-one")))
        ok = report_check("timeout is a HANG", results["hang-me"]["status"] == "HANG", str(results.get("hang-me"))) and ok
        ok = report_check(
            "worker respawns after a hang", results["ok-two"]["status"] == "PASS", str(results.get("ok-two"))
        ) and ok
        ok = report_check(
            "crash is a HANG with a note",
            results["crash-me"]["status"] == "HANG" and "crash" in results["crash-me"]["message"],
            str(results.get("crash-me")),
        ) and ok
        crash_loop = [job_stub(f"crash-{i}") for i in range(MAX_CONSECUTIVE_CRASHES + 2)]
        aborted = False
        try:
            run_batch(crash_loop, 1.0, command)
        except HarnessError:
            aborted = True
        ok = report_check("crash loop aborts", aborted, "run_batch kept spinning") and ok
    finally:
        Path(stub_path).unlink(missing_ok=True)
    return ok


def job_stub(test_id):
    return {
        "id": test_id,
        "path": "",
        "includes": [],
        "flags": [],
        "negativeType": "",
        "negativePhase": "",
        "harnessDir": "",
        "shimDir": "",
    }


def self_test_gate():
    print("self-test — baseline gate")
    saved = BASELINE.read_text(encoding="utf-8") if BASELINE.is_file() else None
    commit = "0" * 40
    try:
        BASELINE.write_text(
            f"# baseline\n# corpus: {commit}\nFAIL  area/known-fail.js\n", encoding="utf-8"
        )
        regression = {
            "area/known-fail.js": {"status": "FAIL", "errorName": "", "message": ""},
            "area/new-fail.js": {"status": "FAIL", "errorName": "", "message": "boom"},
        }
        ok, messages = gate(regression, {}, commit)
        ok_check = report_check("new failure is a regression", not ok and any("REGRESSION" in m for m in messages), str(messages))
        stale = {"area/known-fail.js": {"status": "PASS", "errorName": "", "message": ""}}
        ok, messages = gate(stale, {}, commit)
        ok_check = report_check("fixed test is stale", not ok and any("STALE" in m for m in messages), str(messages)) and ok_check
        ok, messages = gate({}, {"area/known-fail.js": "EXCLUDED"}, commit)
        ok_check = report_check(
            "filtered baseline entry fails", not ok and any("FILTERED" in m for m in messages), str(messages)
        ) and ok_check
        ok, messages = gate({"area/known-fail.js": {"status": "FAIL", "errorName": "", "message": ""}}, {}, commit)
        ok_check = report_check("clean run passes the gate", ok and not messages, str(messages)) and ok_check
        ok, messages = gate({}, {}, "f" * 40)
        ok_check = report_check(
            "corpus mismatch fails", not ok and any("different corpus" in m for m in messages), str(messages)
        ) and ok_check
    finally:
        if saved is None:
            BASELINE.unlink(missing_ok=True)
        else:
            BASELINE.write_text(saved, encoding="utf-8")
    return ok_check


def report_check(label, ok, detail=""):
    colour = PASS_COLOUR if ok else FAIL_COLOUR
    print(f"  [{colour}{'ok' if ok else 'FAIL'}{RESET}] {label}" + ("" if ok else f"  {detail[:160]}"))
    return ok


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def parse_args(argv):
    parser = argparse.ArgumentParser(description="test262 conformance harness for SimpleJS")
    parser.add_argument("--fetch", action="store_true", help="download, verify and extract the pinned corpus")
    parser.add_argument("--filter", default=None, help="only run tests whose id contains this substring")
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4, help="parallel worker JVMs")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="per-test hard timeout, seconds")
    parser.add_argument("--update-baseline", action="store_true", help="rewrite the baseline from this run")
    parser.add_argument("--gate", choices=["baseline", "none"], default="none", help="fail on new failures")
    parser.add_argument("--self-test", action="store_true", help="check the harness itself against its fixtures")
    parser.add_argument("--self-check", action="store_true", help="assert the known divergences still fail")
    parser.add_argument(
        "--dump-failures",
        action="store_true",
        help="run only the baselined tests and write test_log/test262-failures.tsv",
    )
    parser.add_argument(
        "--max-crashes",
        type=int,
        default=MAX_CONSECUTIVE_CRASHES,
        help="abort after this many workers die in a row (timeouts do not count)",
    )
    parser.add_argument("--require-corpus", action="store_true", help="fail instead of skipping when absent")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    signal.signal(signal.SIGINT, lambda *_: (reap_workers(), sys.exit(130)))
    try:
        props = load_properties()
        if args.fetch:
            fetch(props)
            return 0
        if args.self_test:
            return 0 if self_test() else 1
        commit = props["test262.commit"]
        if corpus_stamp() != commit:
            message = (
                f"corpus missing or stale under {CORPUS.relative_to(ROOT)} "
                f"(want {commit[:12]}, have {corpus_stamp()[:12] or 'nothing'}). "
                "Run: python3 test_utils/test262.py --fetch"
            )
            if args.require_corpus:
                print(f"{FAIL_COLOUR}{message}{RESET}")
                return 1
            print(f"{WARN_COLOUR}{message}{RESET}")
            return 0
        return run(args, commit)
    except HarnessError as error:
        print(f"{FAIL_COLOUR}harness error:{RESET} {error}")
        return 1
    finally:
        reap_workers()


def run(args, commit):
    exclusions = load_exclusions()
    print(f"collecting tests from {CORPUS.relative_to(ROOT)}/test")
    collected = collect(CORPUS / "test", CORPUS / "harness", exclusions, args.filter, known_features(exclusions))
    print(
        f"  {len(collected['jobs'])} to run, {len(collected['skipped'])} skipped, "
        f"{len(collected['excluded'])} excluded"
    )
    if args.dump_failures:
        return run_dump_failures(collected, args)
    started = time.monotonic()
    results = run_jobs(collected["jobs"], args.timeout, args.jobs, args.max_crashes)
    elapsed = time.monotonic() - started
    filtered = {test_id: "SKIP" for test_id, _ in collected["skipped"]}
    filtered.update({test_id: "EXCLUDED" for test_id, _ in collected["excluded"]})
    if args.update_baseline:
        write_baseline(results, commit)
        write_features(collected, commit)
    gate_ok, gate_messages = (True, [])
    if args.gate == "baseline" and not args.update_baseline:
        gate_ok, gate_messages = gate(results, filtered, commit)
    report = build_report(results, collected, commit, elapsed, gate_messages)
    print(f"\nreport written to {REPORT.relative_to(ROOT)}")
    print(summary_block(report))
    write_step_summary(report)
    check_ok = self_check(results) if args.self_check else True
    for message in gate_messages:
        print(f"  {FAIL_COLOUR}{message}{RESET}")
    if not gate_ok:
        print(f"\n{FAIL_COLOUR}gate failed: {len(gate_messages)} problem(s) above{RESET}")
    return 0 if gate_ok and check_ok else 1


def run_dump_failures(collected, args):
    baseline, _ = load_baseline()
    if not baseline:
        raise HarnessError(f"{BASELINE.relative_to(ROOT)} has no entries — nothing to dump")
    jobs = [job for job in collected["jobs"] if job["id"] in baseline]
    if not jobs:
        raise HarnessError("no baselined test survived the filter")
    print(f"re-running {len(jobs)} baselined test(s) of {len(baseline)}")
    results = run_jobs(jobs, args.timeout, args.jobs, args.max_crashes)
    dump_failures(jobs, results)
    return 0


def summary_block(report):
    lines = report.splitlines()
    start = next((i for i, line in enumerate(lines) if line.startswith("## Totals")), 0)
    end = next((i for i, line in enumerate(lines[start + 1 :], start + 1) if line.startswith("## Per area")), len(lines))
    return "\n".join(lines[start:end])


def write_step_summary(report):
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report + "\n")


if __name__ == "__main__":
    sys.exit(main())
