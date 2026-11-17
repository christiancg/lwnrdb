from __future__ import annotations

import ssl
from collections.abc import Sequence
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DEFAULT_PORT = 8989
DEFAULT_POOL_SIZE = 8
DEFAULT_CONNECT_TIMEOUT = 10.0
DEFAULT_REQUEST_TIMEOUT = 330.0
DEFAULT_ACQUIRE_TIMEOUT = 30.0
DEFAULT_SHUTDOWN_TIMEOUT = 10.0
DEFAULT_MAX_REPLY_BYTES = 256 * 1024 * 1024
_MAX_PORT = 65_535
_MAX_DOUBLINGS = 30


def _positive_integer(name: str, value: object) -> int:
    if type(value) is not int or value < 1:
        raise ValueError(f"{name} must be an integer of at least 1: {value!r}")
    return value


def _positive_seconds(name: str, value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float) or not 0 < value < float("inf"):
        raise ValueError(f"{name} must be a positive, finite number of seconds: {value!r}")
    return float(value)


@dataclass(frozen=True, slots=True)
class ServerAddress:
    host: str
    port: int = DEFAULT_PORT

    def __post_init__(self) -> None:
        if not isinstance(self.host, str) or not self.host:
            raise ValueError("a host needs a name or an address")
        if type(self.port) is not int or not 1 <= self.port <= _MAX_PORT:
            raise ValueError(f"port must be between 1 and 65535: {self.port!r}")


@dataclass(frozen=True, slots=True)
class Credentials:
    username: str
    password: str = field(repr=False)

    def __post_init__(self) -> None:
        if not isinstance(self.username, str) or not isinstance(self.password, str):
            raise TypeError("credentials need a username and a password")


@dataclass(frozen=True, slots=True)
class TlsSettings:
    ca_file: str | None = None
    ca_data: str | None = None
    server_hostname: str | None = None
    insecure: bool = False
    context: ssl.SSLContext | None = None


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    max_attempts: int = 5
    initial_backoff: float = 0.05
    max_backoff: float = 2.0

    def __post_init__(self) -> None:
        _positive_integer("max_attempts", self.max_attempts)
        backoffs = (self.initial_backoff, self.max_backoff)
        if any(isinstance(value, bool) or not isinstance(value, int | float) for value in backoffs) or not (
            0 <= self.initial_backoff <= self.max_backoff < float("inf")
        ):
            raise ValueError("backoffs must satisfy 0 <= initial_backoff <= max_backoff")

    def backoff_before(self, attempt: int) -> float:
        doublings = min(max(attempt - 1, 0), _MAX_DOUBLINGS)
        return float(min(self.initial_backoff * 2**doublings, self.max_backoff))


DEFAULT_RETRY_POLICY = RetryPolicy()
NO_RETRIES = RetryPolicy(max_attempts=1, initial_backoff=0.0, max_backoff=0.0)


@dataclass(frozen=True, slots=True)
class ClientSettings:
    hosts: Sequence[ServerAddress] = (ServerAddress("localhost"),)
    credentials: Credentials | None = None
    tls: TlsSettings | None = None
    pool_size: int = DEFAULT_POOL_SIZE
    connect_timeout: float = DEFAULT_CONNECT_TIMEOUT
    request_timeout: float = DEFAULT_REQUEST_TIMEOUT
    acquire_timeout: float = DEFAULT_ACQUIRE_TIMEOUT
    shutdown_timeout: float = DEFAULT_SHUTDOWN_TIMEOUT
    max_reply_bytes: int = DEFAULT_MAX_REPLY_BYTES
    retry_policy: RetryPolicy = DEFAULT_RETRY_POLICY
    temporal_zone: str | None = None

    def __post_init__(self) -> None:
        hosts = tuple(self.hosts)
        if not hosts:
            raise ValueError("hosts must name at least one server")
        if not all(isinstance(host, ServerAddress) for host in hosts):
            raise TypeError("hosts must be ServerAddress values")
        object.__setattr__(self, "hosts", hosts)
        _positive_integer("pool_size", self.pool_size)
        _positive_integer("max_reply_bytes", self.max_reply_bytes)
        for name in ("connect_timeout", "request_timeout", "acquire_timeout", "shutdown_timeout"):
            object.__setattr__(self, name, _positive_seconds(name, getattr(self, name)))
        if not isinstance(self.retry_policy, RetryPolicy):
            raise TypeError("retry_policy must be a RetryPolicy")
        if self.temporal_zone is not None:
            check_zone(self.temporal_zone)


def check_zone(zone: str) -> ZoneInfo:
    try:
        return ZoneInfo(zone)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValueError(f"temporal_zone is not a known IANA zone: {zone!r}") from error
