from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator

import pytest

from lwnrdb import (
    AsyncDatabase,
    AsyncLwnrdbClient,
    LwnrdbError,
    ProcedureDefinition,
    ScheduleDefinition,
    TriggerDefinition,
    TriggerEvent,
    TriggerRunDecision,
    TriggerRunStatus,
    TriggerTiming,
)
from tests.it.conftest import eventually

pytestmark = pytest.mark.it
SERVER_CONFIG = {"triggersEnabled": "true", "triggerMaxAttempts": "1", "triggerRetryBackoffMs": "100"}


@pytest.fixture
async def shop(client: AsyncLwnrdbClient) -> AsyncIterator[AsyncDatabase]:
    database = client.database("script_it")
    await database.create()
    for name in ("orders", "audit", "notes"):
        await database.collection(name).create()
    yield database
    await database.drop()


async def test_run_script_returns_its_value_and_logs(shop: AsyncDatabase) -> None:
    result = await shop.run_script(
        'import args from "args";\nconsole.log("hi " + args.name);\nreturn { greeting: "hello " + args.name };',
        {"name": "Ana"},
    )
    assert result.value == {"greeting": "hello Ana"}
    assert result.logs[0] == "hi Ana"
    assert (await shop.run_script("return 1 + 1;")).value == 2


async def test_procedures_are_saved_listed_called_and_deleted(shop: AsyncDatabase) -> None:
    procedures = shop.procedures()
    version = await procedures.save(
        ProcedureDefinition("double_it", 'import args from "args";\nreturn args.n * 2;', description="doubles")
    )
    assert version > 0
    assert any(procedure.name == "double_it" for procedure in await procedures.list())
    with_source = [
        procedure for procedure in await procedures.list(include_source=True) if procedure.name == "double_it"
    ]
    assert with_source[0].source
    assert (await procedures.call("double_it", {"n": 21})).value == 42
    with pytest.raises(LwnrdbError):
        await procedures.save(ProcedureDefinition("double_it", "return 0;", if_version=version + 10))
    await procedures.delete("double_it")
    with pytest.raises(LwnrdbError):
        await procedures.call("double_it")


async def test_triggers_fire_test_reject_and_list(shop: AsyncDatabase) -> None:
    await shop.procedures().save(
        ProcedureDefinition(
            "audit_write",
            'import db from "db";\nimport args from "args";\n'
            'db.save(db.name, "audit", { _id: args.id, event: args.event });\nreturn true;',
        )
    )
    await shop.procedures().save(
        ProcedureDefinition(
            "require_total",
            "export default function (document) {\n"
            '  if (document.total == null) { throw new Error("total missing"); }\n  return true;\n}',
        )
    )
    orders = shop.collection("orders")
    saved = await orders.triggers().save(TriggerDefinition("audit", "audit_write", (TriggerEvent.CREATED,)))
    assert saved.version > 0
    await orders.triggers().save(
        TriggerDefinition("guard", "require_total", (TriggerEvent.CREATED,), timing=TriggerTiming.BEFORE)
    )
    assert len(await orders.triggers().list()) == 2
    assert {"audit", "guard"} <= {trigger.name for trigger in await shop.list_triggers()}
    assert (await orders.triggers().test("guard", TriggerEvent.CREATED, {"_id": "t1"})).decision
    with pytest.raises(LwnrdbError, match="total missing") as refused:
        await orders.save({"_id": "o1"})
    assert refused.value.raw_code == "400-21"
    await orders.save({"_id": "o2", "total": 5})
    audit = shop.collection("audit")
    assert await eventually(lambda: audit.find_by_id("o2"), lambda row: row is not None) is not None
    await orders.triggers().delete("audit")
    await orders.triggers().delete("guard")
    assert await orders.triggers().list() == []


async def test_dead_trigger_runs_are_listed_and_resolved(client: AsyncLwnrdbClient, shop: AsyncDatabase) -> None:
    await shop.procedures().save(ProcedureDefinition("always_fails", 'throw new Error("boom");'))
    notes = shop.collection("notes")
    await notes.triggers().save(TriggerDefinition("failing", "always_fails", (TriggerEvent.CREATED,)))
    await notes.save({"_id": "n1"})
    runs = client.admin().trigger_runs()
    dead = await eventually(lambda: runs.list(TriggerRunStatus.DEAD), lambda listed: len(listed) > 0)
    assert dead
    assert len(await runs.list()) >= len(dead)
    assert await runs.resolve(dead[0].run_id, TriggerRunDecision.DISCARD)


async def test_schedules_are_saved_listed_and_deleted(shop: AsyncDatabase) -> None:
    await shop.procedures().save(ProcedureDefinition("noop", "return 0;"))
    schedules = shop.schedules()
    assert await schedules.save(ScheduleDefinition("hourly", "noop", cron="0 * * * *", args={"a": 1})) > 0
    await schedules.save(ScheduleDefinition("poll", "noop", interval_ms=300_000))
    assert len(await schedules.list()) == 2
    assert any("args" in schedule.raw for schedule in await schedules.list(include_args=True))
    await schedules.delete("hourly")
    await schedules.delete("poll")
    assert await schedules.list() == []


async def test_a_long_script_is_listed_and_cancelled(client: AsyncLwnrdbClient, shop: AsyncDatabase) -> None:
    running = asyncio.create_task(shop.run_script("let i = 0;\nwhile (true) { i++; }"))
    scripts = await eventually(lambda: client.admin().scripts().list(), lambda listed: len(listed) > 0)
    assert await client.admin().scripts().cancel(scripts[0].run_id)
    with contextlib.suppress(LwnrdbError):
        await running
