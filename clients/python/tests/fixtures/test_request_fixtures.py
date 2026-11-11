from __future__ import annotations

import json
from collections.abc import Iterator

import pytest

from lwnrdb import OPERATIONS
from lwnrdb._internal.portal import SyncRuntime
from tests.fixtures.recorder import record_async, record_sync
from tests.fixtures.wire_cases import all_cases
from tests.support.json_trees import equivalent
from tests.support.protocol_fixtures import FixtureCase, requests

CASES = all_cases()
FIXTURES = requests()


@pytest.fixture(scope="module")
def runtime() -> Iterator[SyncRuntime]:
    started = SyncRuntime()
    yield started
    started.stop(5.0)


def _assert_produces(fixture: FixtureCase, actual: object) -> None:
    expected = fixture.body["request"]
    assert equivalent(expected, actual), (
        f"{fixture.name}\nexpected {json.dumps(expected)}\nactual   {json.dumps(actual, default=str)}"
    )


@pytest.mark.parametrize("fixture", FIXTURES, ids=[fixture.name for fixture in FIXTURES])
async def test_the_async_api_produces_the_fixture(fixture: FixtureCase) -> None:
    call = CASES.get(fixture.name)
    assert call is not None, f"{fixture.name} has no builder mapping"
    recorder = await record_async(call)
    _assert_produces(fixture, recorder.channel.last())


@pytest.mark.parametrize("fixture", FIXTURES, ids=[fixture.name for fixture in FIXTURES])
def test_the_sync_api_produces_the_fixture(fixture: FixtureCase, runtime: SyncRuntime) -> None:
    call = CASES.get(fixture.name)
    assert call is not None, f"{fixture.name} has no builder mapping"
    recorder = record_sync(call, runtime)
    _assert_produces(fixture, recorder.channel.last())


def test_every_mapping_names_an_existing_fixture() -> None:
    names = {fixture.name for fixture in FIXTURES}
    assert [name for name in CASES if name not in names] == []


async def test_every_operation_is_produced_by_some_mapping() -> None:
    produced: set[object] = set()
    for call in CASES.values():
        recorder = await record_async(call)
        produced.update(request.get("type") for request in recorder.channel.requests)
    assert sorted(str(name) for name in produced) == sorted(operation.name for operation in OPERATIONS)
