import time

from .ops import repair


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
