from __future__ import annotations

from dataclasses import dataclass

from lwnrdb._catalogue import ERROR_CODES as _CATALOGUE
from lwnrdb._catalogue import ErrorCode
from lwnrdb.status import Status

__all__ = ["ERROR_CODES", "RETRYABLE_CODES", "ErrorCode", "ErrorCodeDetails", "error_code_details"]


@dataclass(frozen=True, slots=True)
class ErrorCodeDetails:
    name: str
    code: str
    status: Status
    retryable: bool
    message: str | None


ERROR_CODES: tuple[ErrorCodeDetails, ...] = tuple(
    ErrorCodeDetails(name, code, Status(status), retryable, message)
    for name, code, status, retryable, message in _CATALOGUE
)

_BY_CODE = {details.code: details for details in ERROR_CODES}

RETRYABLE_CODES: frozenset[str] = frozenset(details.code for details in ERROR_CODES if details.retryable)


def error_code_details(code: str | None) -> ErrorCodeDetails | None:
    return None if code is None else _BY_CODE.get(code)
