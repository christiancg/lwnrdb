import {
    and,
    field,
    GeoPoint,
    LocalDateTime,
    LwnrdbClient,
    MapOps,
    MidOps,
    Order,
    type DocumentCodec,
    type Stored,
    type Subscription,
} from "lwnrdb-client";

interface Placed {
    readonly _id?: string;
    readonly status: "active" | "closed";
    readonly total: number;
    readonly placedAt: LocalDateTime;
    readonly location: GeoPoint;
}

interface Invoice {
    readonly id: string;
    readonly cents: number;
}

const invoices: DocumentCodec<Invoice> = {
    encode: (invoice) => ({ _id: invoice.id, cents: invoice.cents }),
    decode: (document) => ({ id: String(document["_id"]), cents: Number(document["cents"]) }),
};

export async function topActive(client: LwnrdbClient): Promise<Stored<Placed>[]> {
    const orders = client.database("shop").collection<Placed>("orders");
    await orders.save({
        status: "active",
        total: 10,
        placedAt: LocalDateTime.of(2024, 1, 1, 9, 0),
        location: GeoPoint.of(1, 2),
    });
    const rows = await orders
        .query()
        .filter(and(field("status").eq("active"), field("total").gt(5)))
        .map(MapOps.set("double", MidOps.multiply("total", 2)))
        .sort("total", Order.DESC)
        .limit(10)
        .toArray();
    const id: string = rows[0]?._id ?? "";
    const when: LocalDateTime | undefined = rows[0]?.placedAt;
    void id;
    void when;
    return rows;
}

export async function invoiceTotal(client: LwnrdbClient): Promise<number> {
    const collection = client.database("billing").collection("invoices", invoices);
    const invoice: Invoice | undefined = await collection.findById("i-1");
    return invoice?.cents ?? 0;
}

export async function watch(client: LwnrdbClient, totals: number[]): Promise<Subscription> {
    await using transaction = await client.beginTransaction();
    await transaction.collection<Placed>("shop", "orders").delete("o-1");
    await transaction.commit();
    const orders = client.database("shop").collection<Placed>("orders");
    return orders.listen({
        onUpdate: (update) => {
            totals.push(...update.results.map((row) => row.total));
        },
    });
}
