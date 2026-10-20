from typing import Optional

from engines.base import COLLECTION, DATABASE, INDEXED_FIELDS, Engine


class MongoEngine(Engine):
    name = "mongo"

    def __init__(self, host: str = "mongo"):
        super().__init__(host)
        self.client = None
        self.collection = None

    def connect(self) -> None:
        from pymongo import MongoClient
        self.close()
        journal = {"journal": True} if self.synced else {}
        self.client = MongoClient(f"mongodb://{self.host}:27017", serverSelectionTimeoutMS=2000, maxPoolSize=1,
                                  **journal)
        self.client.admin.command("ping")
        self.collection = self.client[DATABASE][COLLECTION]

    def close(self) -> None:
        if self.client is not None:
            self.client.close()
        self.client = None
        self.collection = None

    def setup(self) -> None:
        self.collection.drop()
        for field in INDEXED_FIELDS:
            self.collection.create_index(field)

    def bulk_insert(self, docs: list) -> None:
        self.collection.insert_many(docs, ordered=False)

    def insert(self, doc: dict) -> None:
        self.collection.insert_one(doc)

    def get(self, key: str) -> Optional[dict]:
        return self.collection.find_one({"_id": key})

    def update(self, doc: dict) -> bool:
        return self.collection.replace_one({"_id": doc["_id"]}, doc).matched_count == 1

    def delete(self, key: str) -> bool:
        return self.collection.delete_one({"_id": key}).deleted_count == 1

    def find_eq(self, category: str, limit: int) -> list:
        return list(self.collection.find({"category": category}).limit(limit))

    def find_range(self, low: float, high: float, limit: int) -> list:
        return list(self.collection.find({"price": {"$gte": low, "$lt": high}}).sort("price", 1).limit(limit))

    def count_range(self, low: float) -> int:
        return self.collection.count_documents({"price": {"$gte": low}})

    def scan_count(self, qty: int) -> int:
        return self.collection.count_documents({"qty": qty})

    def count_all(self) -> int:
        return self.collection.count_documents({})
