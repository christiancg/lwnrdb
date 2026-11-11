from __future__ import annotations

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.descriptors import schedule
from lwnrdb._internal.response_decoder import documents, number_of
from lwnrdb.scripting_types import ScheduleDefinition, ScheduleInfo


class AsyncSchedules:
    def __init__(self, context: ClientContext, database: str) -> None:
        self._context = context
        self._database = database

    async def save(self, definition: ScheduleDefinition) -> int:
        reply = await self._context.channel.send(
            "SAVE_SCHEDULE", self._context.scripts.save_schedule(self._database, definition)
        )
        return int(number_of(reply.get("version")) or 0)

    async def delete(self, name: str) -> None:
        await self._context.channel.send("DELETE_SCHEDULE", self._context.scripts.delete_schedule(self._database, name))

    async def list(self, include_args: bool | None = None) -> list[ScheduleInfo]:
        reply = await self._context.channel.send(
            "LIST_SCHEDULES", self._context.scripts.list_schedules(self._database, include_args)
        )
        return [schedule(row) for row in documents(reply, "schedules")]
