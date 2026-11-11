from __future__ import annotations

from lwnrdb._internal.custom_values import looks_custom, parse_custom_exact


def _same_custom_value(left: str, right: str) -> bool:
    return parse_custom_exact(left) == parse_custom_exact(right)


def equivalent(left: object, right: object) -> bool:
    if isinstance(left, bool) or isinstance(right, bool):
        return left is right
    if isinstance(left, int | float) and isinstance(right, int | float):
        return left == right
    if isinstance(left, str) and isinstance(right, str):
        return left == right or (looks_custom(left) and looks_custom(right) and _same_custom_value(left, right))
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(equivalent(a, b) for a, b in zip(left, right, strict=True))
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(equivalent(left[key], right[key]) for key in left)
    return left is None and right is None
