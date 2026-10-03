import random

from base_utils import check, section

from . import ops as op_kinds
from .ops import Op, generate_plan, plan_literal, repair
from .shrink import Budget, ddmin, matches_signature, signature_of
from .values import RawJson, to_json_text


class FakeDivergence:
    def __init__(self, oracle: str):
        self.oracle = oracle


def _databases(count: int) -> list:
    return [Op(kind=op_kinds.CREATE_DATABASE, db=f"db{index}") for index in range(count)]


def _test_ddmin_finds_the_known_minimum() -> None:
    plan = _databases(24)
    required = {"db7", "db19"}

    def still_fails(candidate: list) -> bool:
        return required.issubset({op.db for op in candidate})

    minimal = ddmin(plan, still_fails, Budget(20.0))
    check("ddmin reduces 24 ops to the 2 that matter",
          {op.db for op in minimal} == required,
          f"got {sorted(op.db for op in minimal)}")


def _test_ddmin_keeps_a_plan_it_cannot_reduce() -> None:
    plan = _databases(6)
    minimal = ddmin(plan, lambda candidate: len(candidate) == 6, Budget(10.0))
    check("ddmin returns the original when no subset reproduces", minimal == plan,
          f"got {len(minimal)} ops")


def _test_ddmin_respects_its_budget() -> None:
    budget = Budget(0.0)
    plan = _databases(12)
    minimal = ddmin(plan, lambda candidate: True, budget)
    check("ddmin with an exhausted budget does no replays",
          minimal == plan and budget.replays == 0, f"replays={budget.replays}")


def _test_repair_drops_an_orphaned_write() -> None:
    orphan = Op(kind=op_kinds.SAVE, db="db0", coll="missing", docs=[{"_id": "a"}])
    plan = [Op(kind=op_kinds.CREATE_DATABASE, db="db0"), orphan]
    check("repair drops a save into a collection that was never created",
          repair(plan) == plan[:1], f"got {repair(plan)}")


def _test_repair_drops_writes_after_a_drop() -> None:
    plan = [
        Op(kind=op_kinds.CREATE_DATABASE, db="db0"),
        Op(kind=op_kinds.CREATE_COLLECTION, db="db0", coll="c0"),
        Op(kind=op_kinds.DROP_COLLECTION, db="db0", coll="c0"),
        Op(kind=op_kinds.SAVE, db="db0", coll="c0", docs=[{"_id": "a"}]),
    ]
    check("repair drops a save issued after its collection was dropped",
          repair(plan) == plan[:3], f"got {len(repair(plan))} ops")


def _test_repair_thins_a_transaction() -> None:
    nested = [Op(kind=op_kinds.SAVE, db="db0", coll="gone", docs=[{"_id": "a"}])]
    plan = [Op(kind=op_kinds.CREATE_DATABASE, db="db0"),
            Op(kind=op_kinds.TRANSACTION, db="db0", coll="gone", nested=nested)]
    check("repair drops a transaction whose every op was orphaned",
          repair(plan) == plan[:1], f"got {len(repair(plan))} ops")


def _test_repair_is_idempotent() -> None:
    plan, _ = generate_plan(random.Random("selftest-repair"), 60)
    once = repair(plan)
    check("repair is idempotent", repair(once) == once,
          f"{len(once)} ops then {len(repair(once))}")


def _test_generation_is_deterministic() -> None:
    first, _ = generate_plan(random.Random("selftest-determinism"), 50)
    second, _ = generate_plan(random.Random("selftest-determinism"), 50)
    check("generate_plan is deterministic for one seed", first == second)


def _test_namespace_is_deterministic() -> None:
    _, first = generate_plan(random.Random("selftest-namespace"), 20)
    _, second = generate_plan(random.Random("selftest-namespace"), 20)
    check("the namespace is deterministic for one seed",
          (first.databases, first.collections, first.fields, first.ids)
          == (second.databases, second.collections, second.fields, second.ids))


def _test_plan_survives_its_literal_form() -> None:
    plan = repair(generate_plan(random.Random("selftest-literal"), 40)[0])
    scope = {"Op": Op, "RawJson": RawJson}
    exec(plan_literal(plan), scope)
    check("a plan round-trips through its printed literal", scope["PLAN"] == plan)


def _test_raw_json_survives_serialisation() -> None:
    document = {"_id": "a", "big": RawJson("1e400"), "nested": [RawJson("1e400")]}
    text = to_json_text(document)
    check("to_json_text emits a raw token json.dumps cannot express",
          '"big":1e400' in text and "[1e400]" in text, text)


def _test_signature_matching() -> None:
    divergences = [FakeDivergence("count-vs-rows"), FakeDivergence("index-vs-scan")]
    check("signature_of takes the first divergence",
          signature_of(divergences) == "count-vs-rows")
    check("matches_signature finds a later divergence of the same oracle",
          matches_signature([FakeDivergence("index-vs-scan")], "index-vs-scan"))
    check("matches_signature rejects an unrelated oracle",
          not matches_signature([FakeDivergence("warm-vs-cold")], "index-vs-scan"))
    check("signature_of answers None for a clean run", signature_of([]) is None)


def run_self_test() -> None:
    section("Shrinker")
    _test_ddmin_finds_the_known_minimum()
    _test_ddmin_keeps_a_plan_it_cannot_reduce()
    _test_ddmin_respects_its_budget()
    _test_signature_matching()

    section("Plan repair")
    _test_repair_drops_an_orphaned_write()
    _test_repair_drops_writes_after_a_drop()
    _test_repair_thins_a_transaction()
    _test_repair_is_idempotent()

    section("Determinism")
    _test_generation_is_deterministic()
    _test_namespace_is_deterministic()
    _test_plan_survives_its_literal_form()
    _test_raw_json_survives_serialisation()
