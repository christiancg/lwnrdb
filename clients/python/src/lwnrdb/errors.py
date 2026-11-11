from __future__ import annotations

from lwnrdb.error_codes import ErrorCodeDetails, error_code_details
from lwnrdb.status import Status


class LwnrdbError(Exception):
    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        status: Status | None = None,
        operation: str | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.raw_code = code
        self.status = status
        self.operation = operation

    @property
    def code(self) -> ErrorCodeDetails | None:
        return error_code_details(self.raw_code)

    def has_code(self, code: str) -> bool:
        return self.raw_code == code


class AuthenticationError(LwnrdbError):
    pass


class ForbiddenError(LwnrdbError):
    pass


class NotFoundError(LwnrdbError):
    pass


class ConflictError(LwnrdbError):
    pass


class ListenEndedError(LwnrdbError):
    pass


class UnavailableError(LwnrdbError):
    pass


class UnknownOutcomeError(LwnrdbError):
    pass


class ConnectionLostError(LwnrdbError):
    pass


class RequestTimeoutError(ConnectionLostError):
    pass


class ServerClosedConnectionError(LwnrdbError):
    pass


class PoolExhaustedError(LwnrdbError):
    pass


class MappingError(LwnrdbError):
    pass
