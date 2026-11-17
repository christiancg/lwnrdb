from __future__ import annotations

import pytest

from lwnrdb import (
    AuthenticationError,
    ConflictError,
    ForbiddenError,
    ListenEndedError,
    LwnrdbError,
    NotFoundError,
    Status,
    UnavailableError,
    UnknownOutcomeError,
)
from lwnrdb._internal.response_decoder import (
    boolean_of,
    check,
    document,
    documents,
    number_of,
    raise_for,
    strings,
    text_of,
)


@pytest.mark.parametrize(
    ("code", "status", "kind"),
    [
        ("401-1", "UNAUTHENTICATED", AuthenticationError),
        ("403-1", "FORBIDDEN", ForbiddenError),
        ("404-1", "NOT_FOUND", NotFoundError),
        ("409-4", "ERROR", ConflictError),
        ("410-1", "NOT_FOUND", ListenEndedError),
        ("503-2", "ERROR", UnavailableError),
        ("500-33", "ERROR", UnknownOutcomeError),
        ("500-34", "ERROR", UnknownOutcomeError),
        ("400-1", "ERROR", LwnrdbError),
    ],
)
def test_the_error_class_follows_the_code_range(code: str, status: str, kind: type[LwnrdbError]) -> None:
    error = raise_for({"type": "SAVE", "status": status, "errorCode": code, "message": "no"})
    assert type(error) is kind
    assert error.raw_code == code
    assert error.operation == "SAVE"
    assert str(error) == f"no ({code})"
    assert error.has_code(code)


def test_without_a_code_the_status_picks_the_class() -> None:
    assert type(raise_for({"status": "FORBIDDEN"})) is ForbiddenError
    assert type(raise_for({"status": "UNAUTHENTICATED"})) is AuthenticationError
    assert type(raise_for({"status": "NOT_FOUND"})) is NotFoundError
    error = raise_for({"status": "WEIRD"})
    assert type(error) is LwnrdbError
    assert error.status is Status.ERROR
    assert str(error) == "request failed"


def test_the_message_falls_back_to_the_catalogue_default() -> None:
    error = raise_for({"status": "ERROR", "errorCode": "400-3"})
    assert str(error) == "Duplicate _id in bulk save request (400-3)"
    assert error.code is not None
    assert error.code.name == "DUPLICATE_ID"
    assert raise_for({"status": "ERROR", "errorCode": "999-9"}).code is None


def test_check_passes_successes_and_raises_failures() -> None:
    frame = {"status": "OK"}
    assert check(frame) is frame
    assert check({"type": "X"}) == {"type": "X"}
    assert check({"status": "NO_OPERATION"}) == {"status": "NO_OPERATION"}
    with pytest.raises(NotFoundError):
        check({"status": "NOT_FOUND", "errorCode": "404-2"})


def test_reply_readers_are_lenient() -> None:
    frame = {"rows": [{"a": "#geo(1,2)"}, 3], "one": {"b": 1}, "list": ["x", 1, None, True, {"k": 1}]}
    assert documents(frame, "rows") == [{"a": documents({"r": [{"a": "#geo(1,2)"}]}, "r")[0]["a"]}]
    assert documents(frame, "missing") == []
    assert document(frame, "one") == {"b": 1}
    assert document(frame, "list") is None
    assert strings(frame["list"]) == ("x", "1", "null", "true", '{"k":1}')
    assert strings(None) == ()
    assert text_of(None) is None
    assert text_of(False) == "false"
    assert boolean_of("yes", fallback=True) is True
    assert boolean_of(False, fallback=True) is False
    assert number_of(True) is None
    assert number_of(2.5) == 2.5
