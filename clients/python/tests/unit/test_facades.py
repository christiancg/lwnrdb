from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pytest

from lwnrdb import (
    AsyncAdmin,
    AsyncDatabase,
    Document,
    NotFoundError,
    ProcedureDefinition,
    ScheduleDefinition,
    TriggerDefinition,
    TriggerEvent,
    collection_key,
)
from lwnrdb._internal.values_codec import JsonObject
from tests.support.protocol_fixtures import responses
from tests.support.recording_channel import RecordingChannel, error, ok, recording_context

FRAMES = {fixture.name: fixture.body["frame"] for fixture in responses()}


def _replying(*frames: str) -> RecordingChannel:
    channel = RecordingChannel()
    by_type = {FRAMES[name]["type"]: FRAMES[name] for name in frames}
    channel.replies = lambda request: by_type.get(request["type"], ok(request))
    return channel


def _shop(channel: RecordingChannel) -> AsyncDatabase:
    return AsyncDatabase(recording_context(channel), "shop")


@dataclass
class Order:
    status: str
    id: str | None = None


class Upper:
    def encode(self, value: str) -> Document:
        return {"name": value.upper()}

    def decode(self, document: Document) -> str:
        return str(document.get("name", "")).lower()


async def test_data_replies_decode_to_results() -> None:
    orders = _shop(_replying("ok.save", "ok.bulk_save", "ok.find_by_id")).collection("orders")
    saved = await orders.save({"_id": "o-1"})
    assert (saved.id, saved.inserted) == ("o-1", True)
    bulk = await orders.save_all([{"_id": "o-1"}, {}])
    assert (bulk.inserted, bulk.updated) == (("o-2",), ("o-1",))
    found = await orders.find_by_id("o-1")
    assert found is not None
    assert found["_id"] == "o-1"


async def test_a_missing_document_is_none_only_for_404_2() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: error(request, "NOT_FOUND", "404-2")
    orders = _shop(channel).collection("orders")
    assert await orders.find_by_id("o-1") is None
    channel.replies = lambda request: error(request, "NOT_FOUND", "404-11")
    with pytest.raises(NotFoundError):
        await orders.find_by_id("o-1")


async def test_names_and_ids_are_checked_before_sending() -> None:
    channel = RecordingChannel()
    with pytest.raises(ValueError, match="database name"):
        _shop(channel).__class__(recording_context(channel), "x")
    with pytest.raises(ValueError, match="collection name"):
        _shop(channel).collection("a b")
    orders = _shop(channel).collection("orders")
    with pytest.raises(ValueError, match="_id"):
        await orders.find_by_id("bad id")
    with pytest.raises(ValueError, match="_id"):
        await orders.save({"_id": "x" * 65})
    assert channel.requests == []


async def test_typed_collections_encode_and_decode_through_their_codec() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, results=[{"_id": "o-1", "status": "new"}], object={"name": "ANA"})
    shop = _shop(channel)
    orders = shop.collection("orders", Order)
    await orders.save(Order("new"))
    assert channel.last()["object"] == {"status": "new"}
    assert await orders.query().to_list() == [Order("new", "o-1")]
    names = shop.collection("names", codec=Upper())
    await names.save("ana")
    assert channel.last()["object"] == {"name": "ANA"}
    assert await names.find_by_id("n-1") == "ana"
    with pytest.raises(TypeError, match="not both"):
        shop.collection("x-y", Order, codec=Upper())


async def test_ddl_and_database_replies() -> None:
    shop = _shop(_replying("ok.list_collections", "ok.get_database_stats", "ok.run_script", "ok.list_triggers"))
    assert shop.name == "shop"
    assert await shop.list_collections() == ("orders",)
    assert await shop.stats() == {"memory": {"userCacheBytes": 1}}
    result = await shop.run_script("return 1;")
    assert (result.value, result.logs, result.logs_truncated) == ({"n": 1}, ("x",), False)
    assert [trigger.name for trigger in await shop.list_triggers()] == ["audit"]
    empty = _shop(RecordingChannel())
    assert await empty.stats() == {}


async def test_index_and_schema_replies() -> None:
    orders = _shop(_replying("ok.reindex_primary_key", "ok.save_schema", "ok.get_schema")).collection("orders")
    reindexed = await orders.indexes().reindex_primary_key()
    assert (reindexed.rebuilt_fields, reindexed.primary_key_entries, reindexed.unversioned_entries) == ((), 2, 0)
    assert await orders.schema().save({"type": "object"}) == ("w",)
    assert await orders.schema().get() == {"type": "object"}
    channel = RecordingChannel()
    channel.replies = lambda request: error(request, "NOT_FOUND", "404-12")
    assert await _shop(channel).collection("orders").schema().get() is None


async def test_scripting_replies() -> None:
    shop = _shop(
        _replying(
            "ok.save_procedure", "ok.list_procedures", "ok.call_procedure", "ok.save_schedule", "ok.list_schedules"
        )
    )
    assert await shop.procedures().save(ProcedureDefinition("recalc", "return 1;")) == 4
    procedures = await shop.procedures().list()
    assert (procedures[0].name, procedures[0].version, procedures[0].enabled) == ("recalc", 4, True)
    assert (await shop.procedures().call("recalc")).value == 42
    assert await shop.schedules().save(ScheduleDefinition("nightly", "rollup", interval_ms=1000)) == 3
    schedules = await shop.schedules().list()
    assert (schedules[0].name, schedules[0].cron, schedules[0].enabled) == ("nightly", None, True)


async def test_trigger_replies() -> None:
    triggers = (
        _shop(_replying("ok.save_trigger", "ok.list_triggers", "ok.test_trigger")).collection("orders").triggers()
    )
    saved = await triggers.save(TriggerDefinition("audit", "recalc", (TriggerEvent.CREATED,)))
    assert (saved.version, saved.definer) == (2, "alice")
    listed = await triggers.list()
    assert (listed[0].timing, listed[0].mode, listed[0].events) == ("after", "document", ())
    tested = await triggers.test("audit", TriggerEvent.CREATED, {"_id": "o-1"})
    assert (tested.decision, tested.document) == ("replace", {"_id": "o-1"})


async def test_definitions_are_validated() -> None:
    shop = _shop(RecordingChannel())
    with pytest.raises(ValueError, match="at least one event"):
        await shop.collection("orders").triggers().save(TriggerDefinition("a", "b", ()))
    with pytest.raises(ValueError, match="exactly one of cron and interval_ms"):
        await shop.schedules().save(ScheduleDefinition("a", "b"))
    with pytest.raises(ValueError, match="exactly one"):
        await shop.schedules().save(ScheduleDefinition("a", "b", cron="* * * * *", interval_ms=5))


async def test_admin_replies() -> None:
    channel = _replying(
        "ok.list_users", "ok.list_transactions", "ok.list_scripts", "ok.cancel_script", "ok.list_trigger_runs"
    )
    by_type: dict[Any, JsonObject] = {"RESOLVE_TRIGGER_RUN": FRAMES["ok.resolve_trigger_run"]}
    replies = channel.replies
    channel.replies = lambda request: by_type.get(request["type"]) or replies(request)
    admin = AsyncAdmin(recording_context(channel))
    users = await admin.users().list()
    assert (users[0].username, users[0].admin, users[0].database_permissions) == ("alice", True, {})
    transactions = await admin.transactions().list()
    assert (transactions[0].age_ms, transactions[0].coordinator_reachable) == (42000, False)
    scripts = await admin.scripts().list()
    assert scripts[0].kind == "RUN_SCRIPT"
    assert await admin.scripts().cancel("r")
    runs = await admin.trigger_runs().list()
    assert (runs[0].run_id, runs[0].status, runs[0].attempts) == ("8f0e", "DEAD", 0)
    assert await admin.trigger_runs().resolve("8f0e", "replay")


@pytest.mark.parametrize("code", ["404-5", "404-3"])
async def test_an_empty_user_list_is_empty_for_404_5_and_404_3(code: str) -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: error(request, "NOT_FOUND", code)
    assert await AsyncAdmin(recording_context(channel)).users().list() == []


def test_collection_key_joins_database_and_collection() -> None:
    assert collection_key("shop", "orders") == "shop|orders"
