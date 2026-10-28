import importlib

from engines.base import Engine

ENGINES = {
    "lwnrdb": ("engines.lwnrdb_engine", "LwnrdbEngine"),
    "mysql": ("engines.sql_engines", "MysqlEngine"),
    "postgres": ("engines.sql_engines", "PostgresEngine"),
    "mongo": ("engines.mongo_engine", "MongoEngine"),
    "couchbase": ("engines.couchbase_engine", "CouchbaseEngine"),
}


def create(name: str, durability: str = "relaxed") -> Engine:
    module_name, class_name = ENGINES[name]
    engine = getattr(importlib.import_module(module_name), class_name)()
    engine.synced = durability == "synced"
    return engine
