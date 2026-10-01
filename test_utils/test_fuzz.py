import argparse
import os
import random
import shutil
import sys
import tempfile
import time

import base_utils as bu
from base_utils import check, section

from fuzz import oracles, write_paths
from fuzz.execute import Executor
from fuzz.ops import generate_plan, plan_literal, repair
from fuzz.shrink import Budget as ShrinkBudget
from fuzz.shrink import ddmin, matches_signature, signature_of
from fuzz.regression_seeds import REGRESSION_SEEDS

HOST = "127.0.0.1"
PORT = int(os.environ.get("FUZZ_TEST_PORT", "8994"))
JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"

DEFAULT_OPS = 80
DEFAULT_BUDGET_SECONDS = 180
DEFAULT_SHRINK_SECONDS = 300.0

bu.configure(host=HOST, port=PORT)


class Budget:
    def __init__(self, seconds: float):
        self.deadline = time.time() + seconds

    def remaining(self) -> bool:
        return time.time() < self.deadline


def query_rng(seed) -> random.Random:
    return random.Random(f"queries-{seed}")


def build_plan(seed, ops: int):
    plan, namespace = generate_plan(random.Random(f"plan-{seed}"), ops)
    return repair(plan), namespace


def wants(signature, family: str) -> bool:
    return signature is None or signature.startswith(family)


def check_all(executor, outcome, namespace, seed, stats, signature=None) -> list:
    matrices = oracles.build_matrices(outcome, query_rng(seed))
    divergences = []
    if wants(signature, "write-path"):
        divergences.extend(write_paths.compare(
            executor, random.Random(f"writepaths-{seed}"), namespace, stats))
    if wants(signature, "index-vs-scan"):
        divergences.extend(oracles.index_vs_scan(executor, matrices, stats))
    if wants(signature, "count-vs-rows"):
        divergences.extend(oracles.count_vs_rows(executor, matrices, stats))
    if wants(signature, "not-equals-vs-not-in"):
        divergences.extend(oracles.not_equals_vs_not_in(executor, matrices, stats))
    if wants(signature, "warm-vs-cold"):
        divergences.extend(oracles.warm_vs_cold(executor, matrices, stats))
    return divergences


def evaluate(executor, plan, namespace, seed, stats, signature=None):
    outcome = executor.apply(plan)
    executor.settle_indexes(outcome, namespace.fields)
    return check_all(executor, outcome, namespace, seed, stats, signature), outcome


def run_plan(plan: list, namespace, seed, signature=None):
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-fuzz-")
    executor = Executor(work_dir, PORT)
    stats = oracles.Stats()
    try:
        executor.start()
        divergences, outcome = evaluate(executor, plan, namespace, seed, stats, signature)
        return divergences, outcome, stats
    finally:
        try:
            executor.stop()
        except Exception:
            pass
        if not os.environ.get("FUZZ_KEEP_DIRS"):
            shutil.rmtree(work_dir, ignore_errors=True)


class PersistentReplayer:
    def __init__(self, namespace, seed, signature):
        self.namespace = namespace
        self.seed = seed
        self.signature = signature
        self.replays = 0
        self.work_dir = tempfile.mkdtemp(prefix="lwnrdb-shrink-")
        self.executor = Executor(self.work_dir, PORT)
        self.executor.start()

    def __call__(self, plan: list) -> bool:
        self.executor.wipe()
        self.replays += 1
        divergences, _ = evaluate(self.executor, plan, self.namespace, self.seed,
                                  oracles.Stats(), self.signature)
        return matches_signature(divergences, self.signature)

    def close(self) -> None:
        try:
            self.executor.stop()
        except Exception:
            pass
        finally:
            shutil.rmtree(self.work_dir, ignore_errors=True)


def fresh_replayer(namespace, seed, signature):
    def replay(plan: list) -> bool:
        divergences, _, _ = run_plan(plan, namespace, seed, signature)
        return matches_signature(divergences, signature)
    return replay


def shrink_plan(plan, namespace, seed, divergences, seconds: float):
    signature = signature_of(divergences)
    budget = ShrinkBudget(seconds)
    try:
        replayer = PersistentReplayer(namespace, seed, signature)
    except Exception as exc:
        return plan, budget, f"not shrunk, the replay server would not start: {exc}"
    try:
        if replayer(plan):
            minimal = ddmin(plan, replayer, budget)
            if fresh_replayer(namespace, seed, signature)(minimal):
                return minimal, budget, "tier-1"
    except Exception as exc:
        return plan, budget, f"tier-1 aborted, {exc}"
    finally:
        replayer.close()
    if not budget.remaining():
        return plan, budget, "budget exhausted before tier-2"
    try:
        return (ddmin(plan, fresh_replayer(namespace, seed, signature), budget),
                budget, "tier-2")
    except Exception as exc:
        return plan, budget, f"tier-2 aborted, {exc}"


def report_failure(seed, label: str, plan: list, divergences: list, tier: str = "",
                   budget=None) -> None:
    print(f"\n  seed {seed} ({label}) produced {len(divergences)} divergence(s) "
          f"on the full plan:")
    for divergence in divergences[:5]:
        print(f"      - {divergence.describe()}")
    if len(divergences) > 5:
        print(f"      ... and {len(divergences) - 5} more")
    if tier:
        replays = budget.replays if budget else 0
        print(f"\n  {len(plan)} ops after shrinking ({tier}, {replays} replay(s)); "
              f"the counts above belong to the full plan, the query labels to both")
    print(f"\n  reproduction ({len(plan)} ops):\n{plan_literal(plan)}")


def run_seed(seed, label: str, ops: int, shrink_seconds: float = 0.0) -> bool:
    plan, namespace = build_plan(seed, ops)
    try:
        divergences, outcome, stats = run_plan(plan, namespace, seed)
    except Exception as exc:
        check(f"seed {seed} ({label})", False,
              f"the harness could not complete the run: {exc}")
        return False
    detail = f"{len(plan)} ops, {len(outcome.live())} collections, {stats.render()}"
    ok = check(f"seed {seed} ({label})", not divergences, detail)
    if not ok:
        if shrink_seconds > 0:
            print(f"\n  shrinking (budget {shrink_seconds:.0f}s) ...")
            minimal, budget, tier = shrink_plan(plan, namespace, seed, divergences,
                                                shrink_seconds)
            report_failure(seed, label, minimal, divergences, tier, budget)
        else:
            report_failure(seed, label, plan, divergences)
    elif stats.get("index_vs_scan_queries") == 0:
        bu.warn(f"seed {seed} ({label}) compared no index-vs-scan queries",
                "the plan left no indexed collection alive")
    return ok


def run_regression(budget: Budget, ops: int, shrink_seconds: float) -> None:
    section("Regression seeds")
    for seed, label in REGRESSION_SEEDS:
        if not budget.remaining():
            bu.warn(f"seed {seed} ({label}) skipped", "wall-clock budget exhausted")
            continue
        run_seed(seed, label, ops, shrink_seconds)


def run_soak(minutes: float, ops: int, start_seed, shrink_seconds: float) -> None:
    section(f"Soak ({minutes} minutes)")
    budget = Budget(minutes * 60.0)
    rng = random.Random(start_seed)
    attempts = 0
    while budget.remaining():
        seed = rng.randrange(1, 2 ** 31)
        attempts += 1
        if not run_seed(seed, "soak", ops, shrink_seconds):
            print("\n  Stopping the soak on the first failing seed.")
            return
    print(f"\n  {attempts} seed(s) ran clean.")


def require_jar() -> None:
    jar = os.path.join(bu.REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Differential fuzz harness for LWNRDB. One seed determines a whole run "
                    "- the plan of operations, the documents it writes and the queries it "
                    "asks - so a failing plan can be replayed and shrunk. Every oracle "
                    "compares the engine against itself: an index-backed read against the "
                    "same read forced down the scan path, a warm answer against the same "
                    "answer after a restart, an index-only COUNT against the rows it counts, "
                    "and one corpus written as single SAVEs, as BULK_SAVEs and in one "
                    "transaction.",
        epilog="examples: --self-test | --seeds regression --budget 240 "
               "| --seed 12345 --ops 120 | --seed random --minutes 30")
    parser.add_argument("--seed", default=None,
                        help="run one seed; 'random' picks one and prints it")
    parser.add_argument("--seeds", default=None, choices=["regression"],
                        help="run the pinned seed list in fuzz/regression_seeds.py "
                             "(the default when no other mode is given)")
    parser.add_argument("--ops", type=int, default=DEFAULT_OPS,
                        help=f"operations per generated plan (default {DEFAULT_OPS})")
    parser.add_argument("--budget", type=float, default=DEFAULT_BUDGET_SECONDS,
                        help=f"wall-clock seconds for the whole seed list "
                             f"(default {DEFAULT_BUDGET_SECONDS})")
    parser.add_argument("--minutes", type=float, default=None,
                        help="soak on random seeds for this many minutes, stopping at the "
                             "first failure")
    parser.add_argument("--shrink", type=float, default=DEFAULT_SHRINK_SECONDS,
                        help=f"wall-clock seconds to spend reducing a failing plan to a "
                             f"minimal reproduction; 0 disables shrinking "
                             f"(default {DEFAULT_SHRINK_SECONDS:.0f})")
    parser.add_argument("--self-test", action="store_true",
                        help="check the shrinker, the plan repair and generator determinism; "
                             "needs no server and no jar")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.self_test:
        from fuzz.selftest import run_self_test
        bu.banner("fuzz harness self-test", HOST, PORT)
        run_self_test()
        bu.summary()
        return

    require_jar()
    bu.banner("differential fuzz harness", HOST, PORT)

    if args.seeds == "regression":
        run_regression(Budget(args.budget), args.ops, args.shrink)
    elif args.minutes is not None:
        seed = args.seed if args.seed not in (None, "random") else random.randrange(1, 2 ** 31)
        run_soak(args.minutes, args.ops, seed, args.shrink)
    elif args.seed is not None:
        seed = random.randrange(1, 2 ** 31) if args.seed == "random" else int(args.seed)
        section(f"Single seed {seed}")
        run_seed(seed, "explicit", args.ops, args.shrink)
    else:
        run_regression(Budget(args.budget), args.ops, args.shrink)

    bu.summary()


if __name__ == "__main__":
    main()
