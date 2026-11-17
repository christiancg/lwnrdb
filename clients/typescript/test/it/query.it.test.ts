import { beforeAll, describe, expect, test } from "vitest";
import {
    and,
    CastType,
    type Collection,
    type Document,
    field,
    nand,
    nor,
    or,
    Order,
    script,
    type Stored,
    xor,
} from "../../src/index.js";
import { remove, set } from "../../src/query/map-ops.js";
import * as MidOps from "../../src/query/mid-ops.js";
import { ids, sameWithAndWithoutIndex, serverIt, sortedIds } from "./server-it.js";

const it = serverIt();
let orders: Collection<Document, Stored<Document>>;

beforeAll(async () => {
    const database = it.client.database("query_it");
    await database.create();
    orders = database.collection("orders");
    const customers = database.collection("customers");
    await orders.create();
    await customers.create();
    await orders.saveAll(
        Array.from({ length: 20 }, (_, i) => ({
            _id: `o-${String(i).padStart(2, "0")}`,
            status: i % 3 === 0 ? "new" : "paid",
            total: i * 10,
            qty: (i % 4) + 1,
            price: 2.5,
            customerId: `c-${i % 2}`,
            name: `Item ${i}`,
            tags: [`t${i % 2}`],
        })),
    );
    await customers.saveAll([
        { _id: "c-0", city: "Lima" },
        { _id: "c-1", city: "Quito" },
    ]);
});

describe("queries", () => {
    test("field operators answer the same with and without an index", async () => {
        await sameWithAndWithoutIndex(orders, "total", async () =>
            sortedIds(await orders.query().filter(field("total").gt(150)).toArray()),
        );
        expect(
            await sameWithAndWithoutIndex(orders, "total", async () =>
                sortedIds(await orders.query().filter(field("total").gte(160)).toArray()),
            ),
        ).toEqual(["o-16", "o-17", "o-18", "o-19"]);
        expect(
            await sameWithAndWithoutIndex(orders, "total", () => orders.query().filter(field("total").lt(20)).count()),
        ).toBe(2);
        expect(
            await sameWithAndWithoutIndex(orders, "total", () => orders.query().filter(field("total").lte(20)).count()),
        ).toBe(3);
        expect(
            await sameWithAndWithoutIndex(orders, "status", () =>
                orders.query().filter(field("status").eq("NEW")).count(),
            ),
        ).toBe(7);
        expect(
            await sameWithAndWithoutIndex(orders, "status", () =>
                orders.query().filter(field("status").ne("new")).count(),
            ),
        ).toBe(13);
        expect(
            await sameWithAndWithoutIndex(orders, "status", () =>
                orders.query().filter(field("status").in("new", "paid")).count(),
            ),
        ).toBe(20);
        expect(
            await sameWithAndWithoutIndex(orders, "status", () =>
                orders.query().filter(field("status").notIn("new")).count(),
            ),
        ).toBe(13);
        expect(await orders.query().filter(field("name").contains("1")).count()).toBe(11);
        expect(await orders.query().filter(field("tags").contains("t1")).count()).toBe(10);
    });

    test("conjunctions and scripts", async () => {
        const isNew = field("status").eq("new");
        const big = field("total").gte(100);
        expect(await orders.query().filter(and(isNew, big)).count()).toBe(3);
        expect(await orders.query().filter(or(isNew, big)).count()).toBe(14);
        expect(await orders.query().filter(nor(isNew, big)).count()).toBe(6);
        expect(await orders.query().filter(xor(isNew, big)).count()).toBe(11);
        expect(await orders.query().filter(nand(isNew, big)).count()).toBe(17);
        expect(await orders.query().filter(script("export default (doc) => doc.total > 150;")).count()).toBe(4);
    });

    test("sort, skip, limit and first", async () => {
        expect(ids(await orders.query().sort("total", Order.DESC).skip(2).limit(3).toArray())).toEqual([
            "o-17",
            "o-16",
            "o-15",
        ]);
        expect(
            await sameWithAndWithoutIndex(orders, "total", async () =>
                ids(await orders.query().sort("total", Order.ASC).limit(2).toArray()),
            ),
        ).toEqual(["o-00", "o-01"]);
        expect(await orders.query().filter(field("total").gt(1000)).first()).toBeUndefined();
        expect(await orders.query().filter(field("total").gt(1000)).toArray()).toEqual([]);
    });

    test("groupBy, distinct, join and map", async () => {
        const groups = await orders.query().groupBy("status").sort("status", Order.ASC).toDocuments();
        expect(groups).toHaveLength(2);
        expect(groups[0]?.status).toBe("new");
        expect(groups[0]?.group).toHaveLength(7);
        expect(await orders.query().distinct("status").toDocuments()).toHaveLength(2);
        const [joined] = await orders
            .query()
            .filter(field("_id").eq("o-03"))
            .join("customers", "customerId", "_id", "customer")
            .toArray();
        expect((joined?.customer as Document[])[0]?.city).toBe("Quito");
        const [mapped] = await orders
            .query()
            .filter(field("_id").eq("o-04"))
            .map(
                set("line", MidOps.multiply("qty", "price")),
                set("label", MidOps.concat("name", MidOps.literal(" / "), "status")),
                set("text", MidOps.cast("total", CastType.STRING)),
                set("flag", MidOps.sum(1)).when(field("status").eq("paid")),
                remove("tags"),
            )
            .toArray();
        expect(mapped?.line).toBe(2.5);
        expect(mapped?.label).toBe("Item 4 / paid");
        expect(mapped?.text).toBe("40");
        expect(mapped?.flag).toBe(1);
        expect(mapped).not.toHaveProperty("tags");
    });

    test("reduce, analyze and dirty reads", async () => {
        const [sum] = await orders
            .query()
            .reduce("export default (acc, doc) => acc + doc.total;", 0, "sum")
            .toDocuments();
        expect(sum?.sum).toBe(1900);
        const analyzed = await orders.query().filter(field("status").eq("new")).analyze();
        expect(analyzed.results).toHaveLength(7);
        expect(Object.keys(analyzed.analysis).length).toBeGreaterThan(0);
        expect(await orders.query().filter(field("status").eq("new")).dirtyRead().count()).toBe(7);
    });
});
