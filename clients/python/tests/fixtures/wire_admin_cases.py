from __future__ import annotations

from lwnrdb import (
    DataPermission,
    GlobalPermission,
    Permissions,
    ProcedureDefinition,
    ScheduleDefinition,
    ScriptPermission,
    TransactionDecision,
    TriggerDefinition,
    TriggerEvent,
    TriggerMode,
    TriggerRunDecision,
    TriggerRunStatus,
    TriggerTiming,
    field,
)
from tests.fixtures.recorder import FixtureCall

LISTEN_ID = "550e8400-e29b-41d4-a716-446655440000"
RUN_ID = "8f0e1a2b"


def _ddl(cases: dict[str, FixtureCall]) -> None:
    cases["ddl.list_databases"] = lambda r: r.raw(r.encoder.list_databases())
    cases["ddl.create_database"] = lambda r: r.shop.create()
    cases["ddl.drop_database"] = lambda r: r.shop.drop()
    cases["ddl.list_collections"] = lambda r: r.shop.list_collections()
    cases["ddl.create_collection"] = lambda r: r.orders.create()
    cases["ddl.drop_collection"] = lambda r: r.orders.drop()
    cases["indexes.create_one"] = lambda r: r.orders.indexes().create("email")
    cases["indexes.create_many"] = lambda r: r.orders.indexes().create("email", "status")
    cases["indexes.drop"] = lambda r: r.orders.indexes().drop("email")
    cases["indexes.reindex_all"] = lambda r: r.orders.indexes().reindex()
    cases["indexes.reindex_fields"] = lambda r: r.orders.indexes().reindex("email")
    cases["indexes.reindex_primary_key"] = lambda r: r.orders.indexes().reindex_primary_key()
    cases["schema.save"] = lambda r: r.orders.schema().save(
        {"type": "object", "required": ["status"], "properties": {"status": {"type": "string"}}}
    )
    cases["schema.get"] = lambda r: r.orders.schema().get()
    cases["schema.delete"] = lambda r: r.orders.schema().delete()
    cases["info.server_info"] = lambda r: r.raw(r.encoder.server_info())
    cases["info.database_stats_all"] = lambda r: r.raw(r.encoder.database_stats())
    cases["info.database_stats_one"] = lambda r: r.shop.stats()
    cases["info.close_connection"] = lambda r: r.raw(r.encoder.close_connection())


def _listen_and_transactions(cases: dict[str, FixtureCall]) -> None:
    cases["listen.filter"] = lambda r: r.raw(
        r.encoder.listen("shop", "orders", r.orders.query().filter(field("status").eq("pending")).steps)
    )
    cases["listen.all"] = lambda r: r.raw(r.encoder.listen("shop", "orders", r.orders.query().steps))
    cases["listen.stop"] = lambda r: r.raw(r.encoder.stop_listen(LISTEN_ID))
    cases["transactions.start"] = lambda r: r.raw(r.encoder.start_transaction())
    cases["transactions.commit"] = lambda r: r.raw(r.encoder.commit_transaction())
    cases["transactions.rollback"] = lambda r: r.raw(r.encoder.rollback_transaction())
    cases["transactions.list"] = lambda r: r.admin.transactions().list()
    cases["transactions.resolve_commit"] = lambda r: r.admin.transactions().resolve(
        LISTEN_ID, TransactionDecision.COMMIT
    )
    cases["transactions.resolve_abort"] = lambda r: r.admin.transactions().resolve(LISTEN_ID, TransactionDecision.ABORT)


def _procedures(cases: dict[str, FixtureCall]) -> None:
    cases["scripting.run_script"] = lambda r: r.shop.run_script("return 1;", {"id": "o-1"})
    cases["scripting.run_script_no_args"] = lambda r: r.shop.run_script("return 1;")
    cases["scripting.save_procedure"] = lambda r: r.shop.procedures().save(
        ProcedureDefinition("recalc", "return 1;", description="totals", enabled=True, if_version=3)
    )
    cases["scripting.save_procedure_minimal"] = lambda r: r.shop.procedures().save(
        ProcedureDefinition("recalc", "return 1;")
    )
    cases["scripting.delete_procedure"] = lambda r: r.shop.procedures().delete("recalc")
    cases["scripting.list_procedures"] = lambda r: r.shop.procedures().list(include_source=True)
    cases["scripting.list_procedures_default"] = lambda r: r.shop.procedures().list()
    cases["scripting.call_procedure"] = lambda r: r.shop.procedures().call("recalc", {"id": "o-1"})
    cases["scripting.call_procedure_no_args"] = lambda r: r.shop.procedures().call("recalc")


def _triggers_and_schedules(cases: dict[str, FixtureCall]) -> None:
    cases["scripting.save_trigger"] = lambda r: r.orders.triggers().save(
        TriggerDefinition(
            "audit",
            "recalc",
            (TriggerEvent.CREATED, TriggerEvent.UPDATED, TriggerEvent.DELETED),
            timing=TriggerTiming.AFTER,
            mode=TriggerMode.DOCUMENT,
            allow_cascade=False,
            enabled=True,
            if_version=1,
        )
    )
    cases["scripting.save_trigger_before"] = lambda r: r.orders.triggers().save(
        TriggerDefinition("normalize", "normalize", (TriggerEvent.CREATED,), timing=TriggerTiming.BEFORE)
    )
    cases["scripting.save_trigger_batch"] = lambda r: r.orders.triggers().save(
        TriggerDefinition("rollup", "rollup", (TriggerEvent.CREATED,), mode=TriggerMode.BATCH)
    )
    cases["scripting.delete_trigger"] = lambda r: r.orders.triggers().delete("audit")
    cases["scripting.list_triggers_collection"] = lambda r: r.orders.triggers().list()
    cases["scripting.list_triggers_database"] = lambda r: r.shop.list_triggers()
    cases["scripting.test_trigger"] = lambda r: r.orders.triggers().test(
        "normalize", TriggerEvent.CREATED, {"_id": "o-1", "qty": 2}
    )
    cases["scripting.save_schedule_cron"] = lambda r: r.shop.schedules().save(
        ScheduleDefinition(
            "nightly",
            "rollup",
            cron="0 3 * * *",
            args={"days": 1},
            timeout_ms=60_000,
            description="rollup",
            enabled=True,
            if_version=2,
        )
    )
    cases["scripting.save_schedule_interval"] = lambda r: r.shop.schedules().save(
        ScheduleDefinition("poll", "poll", interval_ms=60_000)
    )
    cases["scripting.delete_schedule"] = lambda r: r.shop.schedules().delete("nightly")
    cases["scripting.list_schedules"] = lambda r: r.shop.schedules().list(include_args=True)
    cases["scripting.list_schedules_default"] = lambda r: r.shop.schedules().list()


def _admin_scripts(cases: dict[str, FixtureCall]) -> None:
    cases["scripting.list_scripts"] = lambda r: r.admin.scripts().list()
    cases["scripting.cancel_script"] = lambda r: r.admin.scripts().cancel("3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83")
    cases["scripting.list_trigger_runs"] = lambda r: r.admin.trigger_runs().list()
    cases["scripting.list_trigger_runs_dead"] = lambda r: r.admin.trigger_runs().list(TriggerRunStatus.DEAD)
    cases["scripting.resolve_trigger_run_replay"] = lambda r: r.admin.trigger_runs().resolve(
        RUN_ID, TriggerRunDecision.REPLAY
    )
    cases["scripting.resolve_trigger_run_discard"] = lambda r: r.admin.trigger_runs().resolve(
        RUN_ID, TriggerRunDecision.DISCARD
    )


def _users(cases: dict[str, FixtureCall]) -> None:
    cases["users.authenticate"] = lambda r: r.raw(r.encoder.authenticate("alice", "Secret-1234"))
    cases["users.create"] = lambda r: r.admin.users().create(
        "bob",
        "Secret-1234",
        Permissions(
            admin=False,
            global_permissions=(GlobalPermission.CREATE_DATABASE,),
            databases={"shop": DataPermission.READ_WRITE},
            collections={"shop|orders": DataPermission.READ},
            scripts={"shop": ScriptPermission.MANAGE},
        ),
    )
    cases["users.create_minimal"] = lambda r: r.admin.users().create("bob", "Secret-1234")
    cases["users.delete"] = lambda r: r.admin.users().delete("bob")
    cases["users.change_permissions"] = lambda r: r.admin.users().change_permissions(
        "bob", Permissions(databases={"shop": DataPermission.READ}, scripts={"shop": ScriptPermission.RUN})
    )
    cases["users.set_password_own"] = lambda r: r.raw(r.scripts.set_password("alice", "Secret-1234", "Secret-5678"))
    cases["users.set_password_admin"] = lambda r: r.admin.users().set_password("bob", "Secret-5678")
    cases["users.list"] = lambda r: r.admin.users().list()
    cases["users.list_filtered"] = lambda r: r.admin.users().list(field("admin").eq(True))
    cases["users.set_database_owners"] = lambda r: r.shop.set_owners(["alice", "bob"])


def add_admin_cases(cases: dict[str, FixtureCall]) -> None:
    _ddl(cases)
    _listen_and_transactions(cases)
    _procedures(cases)
    _triggers_and_schedules(cases)
    _admin_scripts(cases)
    _users(cases)
