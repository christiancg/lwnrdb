import time

from .ops import plan_literal, repair


class Budget:
    def __init__(self, seconds: float):
        self.deadline = time.time() + seconds
        self.replays = 0

    def remaining(self) -> bool:
        return time.time() < self.deadline

    def spend(self) -> None:
        self.replays += 1


def _complement_at(plan: list, start: int, size: int) -> list:
    return plan[:start] + plan[start + size:]


def ddmin(plan: list, still_fails, budget: Budget) -> list:
    current = plan
    chunks = 2
    while len(current) >= 2 and budget.remaining():
        size = max(1, len(current) // chunks)
        reduced = None
        for start in range(0, len(current), size):
            if not budget.remaining():
                break
            candidate = repair(_complement_at(current, start, size))
            if not candidate or len(candidate) >= len(current):
                continue
            budget.spend()
            if still_fails(candidate):
                reduced = candidate
                break
        if reduced is not None:
            current = reduced
            chunks = max(chunks - 1, 2)
        elif chunks >= len(current):
            break
        else:
            chunks = min(chunks * 2, len(current))
    return current


def signature_of(divergences: list):
    return divergences[0].oracle if divergences else None


def matches_signature(divergences: list, signature) -> bool:
    return any(divergence.oracle == signature for divergence in divergences)


MAX_LISTED_DIVERGENCES = 5


def listed_divergences(divergences: list) -> list:
    lines = [divergence.describe() for divergence in divergences[:MAX_LISTED_DIVERGENCES]]
    if len(divergences) > MAX_LISTED_DIVERGENCES:
        lines.append(f"... and {len(divergences) - MAX_LISTED_DIVERGENCES} more")
    return lines


def failure_markdown(seed, label: str, plan: list, divergences: list, tier: str) -> str:
    listed = "\n".join(listed_divergences(divergences))
    shrunk = f"{len(plan)} ops after shrinking ({tier})" if tier else f"{len(plan)} ops, not shrunk"
    return (f"## Fuzz seed {seed} ({label}) failed: {signature_of(divergences)}\n\n"
            f"{len(divergences)} divergence(s) on the full plan:\n\n"
            f"```\n{listed}\n```\n\n"
            f"Reproduction, {shrunk}:\n\n"
            f"```python\n{plan_literal(plan)}```\n\n"
            f"Replay locally: `python3 test_utils/test_fuzz.py --seed {seed}`\n\n"
            f"Once fixed, pin it in `test_utils/fuzz/regression_seeds.py`:\n\n"
            f"```python\n({seed}, \"<describe the defect>\"),\n```\n\n")


def clean_markdown(start_seed, attempts: int, minutes: float) -> str:
    return (f"Fuzz soak from start seed {start_seed}: {attempts} seed(s) ran clean "
            f"in {minutes} minutes.\n\n")


def append(path, text: str) -> None:
    if path:
        with open(path, "a", encoding="utf-8") as report:
            report.write(text)
