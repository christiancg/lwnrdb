from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from lwnrdb import (
    AsyncCollection,
    AsyncLwnrdbClient,
    Collection,
    Document,
    GeoPoint,
    ListenUpdate,
    LwnrdbClient,
    Query,
    SaveResult,
    Subscription,
    field,
)


@dataclass
class Shop:
    name: str
    location: GeoPoint
    opened_at: datetime
    id: str | None = None


def typed_sync(client: LwnrdbClient) -> list[Shop]:
    shops: Collection[Shop, Shop] = client.database("geo").collection("shops", Shop)
    saved: SaveResult = shops.save(Shop("a", GeoPoint(1, 2), datetime(2024, 1, 1)))
    del saved
    query: Query[Shop] = shops.query().filter(field("name").eq("a")).limit(5)
    first: Shop | None = query.first()
    del first

    def on_update(update: ListenUpdate[Shop]) -> None:
        del update

    subscription: Subscription = shops.listen(on_update, query)
    subscription.close()
    return query.to_list()


async def typed_async(client: AsyncLwnrdbClient) -> list[Document]:
    raw: AsyncCollection[Document, Document] = client.database("geo").collection("shops")
    async with client.transaction() as transaction:
        await transaction.collection("geo", "shops").delete("s-1")
    return await raw.query().sort("name").to_list()
