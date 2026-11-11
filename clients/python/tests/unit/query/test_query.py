from __future__ import annotations

import pytest

from lwnrdb import AsyncDatabase, MapOps, MidOps, Order, field
from lwnrdb.query.types import CountStep, LimitStep, SkipStep
from tests.support.recording_channel import RecordingChannel, ok, recording_context


def _orders(channel: RecordingChannel):
    return AsyncDatabase(recording_context(channel), "shop").collection("orders")


def test_a_query_is_immutable_and_reusable() -> None:
    base = _orders(RecordingChannel()).query()
    limited = base.limit(5)
    assert base.steps == ()
    assert limited.steps == (LimitStep(5),)
    assert base.skip(1).steps == (SkipStep(1),)
    assert not base.is_dirty_read
    assert limited.dirty_read().is_dirty_read
    assert limited.dirty_read().limit(2).is_dirty_read


@pytest.mark.parametrize(
    ("build", "error"),
    [
        (lambda query: query.limit(0), ValueError),
        (lambda query: query.limit(True), ValueError),
        (lambda query: query.limit(1.5), ValueError),
        (lambda query: query.skip(-1), ValueError),
        (lambda query: query.map(), ValueError),
        (lambda query: query.map("x"), TypeError),
        (lambda query: query.sort("f", "UP"), ValueError),
        (lambda query: query.filter(field("a") == 1), TypeError),
        (lambda query: query.filter("status"), TypeError),
        (lambda query: query.group_by(1), TypeError),
        (lambda query: query.join("c", "l", "r", None), TypeError),
        (lambda query: query.reduce("s", 0, 3), TypeError),
        (lambda query: query.distinct(3), TypeError),
    ],
)
def test_invalid_steps_are_refused(build, error: type[Exception]) -> None:
    with pytest.raises(error):
        build(_orders(RecordingChannel()).query())


def test_sort_accepts_the_order_spelled_as_text() -> None:
    query = _orders(RecordingChannel()).query()
    assert query.sort("f", "DESC").steps == query.sort("f", Order.DESC).steps


async def test_first_sends_limit_one_and_decodes_the_row() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, results=[{"_id": "o-1"}])
    first = await _orders(channel).query().filter(field("a").eq(1)).first()
    assert first == {"_id": "o-1"}
    assert channel.last()["aggregationSteps"][-1] == {"type": "LIMIT", "limit": 1}


async def test_first_is_none_without_rows() -> None:
    assert await _orders(RecordingChannel()).query().first() is None


async def test_count_reads_the_count_row_and_defaults_to_zero() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, results=[{"count": 7.0}])
    query = _orders(channel).query()
    assert await query.count() == 7
    assert query._then(CountStep()).steps == (CountStep(),)
    channel.replies = lambda request: ok(request, results=[{"count": True}])
    assert await query.count() == 0
    channel.replies = ok
    assert await query.count() == 0


async def test_analyze_returns_results_and_the_analysis() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, results=[{"_id": "a"}], analyzeResult={"steps": []})
    analyzed = await _orders(channel).query().analyze()
    assert analyzed.results == ({"_id": "a"},)
    assert analyzed.analysis == {"steps": []}


async def test_to_documents_and_to_list_agree_for_plain_collections() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, results=[{"_id": "a", "at": "#time(10:00)"}])
    query = _orders(channel).query().map(MapOps.set("x", MidOps.abs("y")))
    assert await query.to_documents() == await query.to_list()


async def test_an_empty_aggregate_answers_an_empty_list_only_for_404_3() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: {"status": "NOT_FOUND", "errorCode": "404-3"}
    assert await _orders(channel).query().to_list() == []
    channel.replies = lambda request: {"status": "NOT_FOUND", "errorCode": "404-11"}
    with pytest.raises(Exception, match="404-11"):
        await _orders(channel).query().to_list()
