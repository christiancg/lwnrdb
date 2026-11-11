from __future__ import annotations

import pytest

from lwnrdb import DEFAULT_RETRY_POLICY, NO_RETRIES, ClientSettings, Credentials, RetryPolicy, ServerAddress


def test_defaults() -> None:
    settings = ClientSettings()
    assert settings.hosts == (ServerAddress("localhost", 8989),)
    assert settings.pool_size == 8
    assert settings.request_timeout == 330.0
    assert settings.retry_policy is DEFAULT_RETRY_POLICY
    assert settings.credentials is None


def test_hosts_become_a_tuple_and_timeouts_floats() -> None:
    settings = ClientSettings(hosts=[ServerAddress("a", 1)], connect_timeout=3)
    assert settings.hosts == (ServerAddress("a", 1),)
    assert isinstance(settings.connect_timeout, float)


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"hosts": ()}, "at least one server"),
        ({"pool_size": 0}, "pool_size must be an integer of at least 1"),
        ({"pool_size": True}, "pool_size"),
        ({"max_reply_bytes": 1.5}, "max_reply_bytes"),
        ({"request_timeout": 0}, "request_timeout must be a positive"),
        ({"connect_timeout": float("inf")}, "connect_timeout"),
        ({"acquire_timeout": True}, "acquire_timeout"),
        ({"temporal_zone": "Mars/Base"}, "not a known IANA zone"),
    ],
)
def test_invalid_settings_are_refused(overrides: dict[str, object], message: str) -> None:
    with pytest.raises(ValueError, match=message):
        ClientSettings(**overrides)


def test_hosts_and_retry_policy_must_have_their_types() -> None:
    with pytest.raises(TypeError, match="ServerAddress"):
        ClientSettings(hosts=("localhost",))
    with pytest.raises(TypeError, match="RetryPolicy"):
        ClientSettings(retry_policy=3)


@pytest.mark.parametrize(("host", "port"), [("", 1), ("h", 0), ("h", 65_536), ("h", True)])
def test_a_server_address_is_validated(host: str, port: int) -> None:
    with pytest.raises(ValueError):
        ServerAddress(host, port)


def test_the_password_is_hidden_from_repr() -> None:
    assert "secret" not in repr(Credentials("alice", "secret"))
    with pytest.raises(TypeError):
        Credentials("alice", None)


def test_retry_backoff_doubles_up_to_the_maximum() -> None:
    policy = RetryPolicy(max_attempts=10, initial_backoff=0.1, max_backoff=0.5)
    assert [policy.backoff_before(attempt) for attempt in (1, 2, 3, 4, 100)] == [0.1, 0.2, 0.4, 0.5, 0.5]
    assert NO_RETRIES.max_attempts == 1


@pytest.mark.parametrize(
    "arguments",
    [{"max_attempts": 0}, {"initial_backoff": -1}, {"initial_backoff": 3, "max_backoff": 2}, {"max_backoff": True}],
)
def test_an_invalid_retry_policy_is_refused(arguments: dict[str, object]) -> None:
    with pytest.raises(ValueError):
        RetryPolicy(**arguments)
