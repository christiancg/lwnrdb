from __future__ import annotations

import math
from datetime import datetime, time

import pytest

from lwnrdb import (
    GeoPoint,
    ListenEndedError,
    LwnrdbError,
    UnavailableError,
    UnknownOutcomeError,
    Vector,
)
from lwnrdb._internal.frame_router import Route, is_listen_end, route
from lwnrdb._internal.response_decoder import check, document, raise_for
from lwnrdb.error_codes import RETRYABLE_CODES
from tests.support.protocol_fixtures import FixtureCase, responses

ERROR_PREFIX = "error:"
FIXTURES = responses()


def _fixture(name: str) -> FixtureCase:
    return next(fixture for fixture in FIXTURES if fixture.name == name)


def _expect_error(frame: dict[str, object], code: str) -> None:
    assert route(frame, awaiting_close=False) is Route.RESPONSE
    with pytest.raises(LwnrdbError) as caught:
        check(frame)
    error = caught.value
    assert error.raw_code == code
    assert code in str(error)
    if code in RETRYABLE_CODES:
        assert isinstance(error, UnavailableError)
    if code in {"500-33", "500-34"}:
        assert isinstance(error, UnknownOutcomeError)


@pytest.mark.parametrize("fixture", FIXTURES, ids=[fixture.name for fixture in FIXTURES])
def test_every_response_decodes_as_expected(fixture: FixtureCase) -> None:
    frame = fixture.body["frame"]
    expectation: str = fixture.body["expect"]
    if expectation == "ok":
        assert route(frame, awaiting_close=False) is Route.RESPONSE
        assert check(frame) is frame
    elif expectation == "push":
        assert route(frame, awaiting_close=False) is Route.PUSH
        assert not is_listen_end(frame)
    elif expectation == "listenEnded":
        assert route(frame, awaiting_close=False) is Route.PUSH
        assert isinstance(raise_for(frame), ListenEndedError)
    elif expectation == "serverClosed":
        assert route(frame, awaiting_close=False) is Route.SERVER_CLOSE
    else:
        assert expectation.startswith(ERROR_PREFIX)
        _expect_error(frame, expectation.removeprefix(ERROR_PREFIX))


def test_custom_values_in_a_reply_decode_to_their_types() -> None:
    found = document(_fixture("ok.find_by_id").body["frame"], "object")
    assert found is not None
    assert found["openedAt"] == datetime(2024, 1, 1, 10, 0)
    assert found["opensAt"] == time(9, 30)
    assert found["location"] == GeoPoint(40.71, -74.0)
    embedding = found["embedding"]
    assert embedding == Vector.of(0.12, 4.4e-4, -0.0, 1.0e21)
    assert isinstance(embedding, Vector)
    assert math.copysign(1.0, embedding.component(2)) == -1.0


def test_the_listen_push_fixture_keeps_the_sign_of_minus_zero() -> None:
    row = _fixture("frames.listen_push").body["frame"]["results"][0]
    decoded = document({"row": row}, "row")
    assert decoded is not None
    embedding = decoded["embedding"]
    assert isinstance(embedding, Vector)
    assert math.copysign(1.0, embedding.component(2)) == -1.0
