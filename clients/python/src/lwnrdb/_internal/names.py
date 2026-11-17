from __future__ import annotations

import re

_NAME = re.compile(r"[A-Za-z0-9_-]{3,64}")
_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
_NAME_RULE = "3-64 characters from A-Z a-z 0-9 _ -"
_ID_RULE = "1-64 characters from A-Z a-z 0-9 _ -"


def _check(value: object, pattern: re.Pattern[str], what: str, rule: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f"invalid {what} {value!r}: it must be {rule}")
    return value


def database_name(name: object) -> str:
    return _check(name, _NAME, "database name", _NAME_RULE)


def collection_name(name: object) -> str:
    return _check(name, _NAME, "collection name", _NAME_RULE)


def document_id(value: object) -> str:
    return _check(value, _ID, "_id", _ID_RULE)
