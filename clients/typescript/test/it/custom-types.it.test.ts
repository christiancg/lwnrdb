import { beforeAll, describe, expect, test } from "vitest";
import {
    type Collection,
    CustomType,
    distance,
    type Document,
    field,
    GeoDistanceComparator,
    GeoPoint,
    LocalDateTime,
    LocalTime,
    LwnrdbClient,
    MappingError,
    nearest,
    Order,
    type Stored,
    Vector,
    within,
} from "../../src/index.js";
import { set } from "../../src/query/map-ops.js";
import * as MidOps from "../../src/query/mid-ops.js";
import { ids, sameWithAndWithoutIndex, serverIt, sortedIds } from "./server-it.js";

const NEW_YORK = GeoPoint.of(40.7128, -74.006);
const NEWARK = GeoPoint.of(40.7357, -74.1724);
const LIMA = GeoPoint.of(-12.0464, -77.0428);

interface Place {
    readonly _id: string;
    readonly openedAt: LocalDateTime;
    readonly opensAt: LocalTime;
    readonly location: GeoPoint;
    readonly embedding: Vector;
}

const it = serverIt({ maxVectorDimensions: "8" });
let places: Collection<Document, Stored<Document>>;

beforeAll(async () => {
    const database = it.client.database("custom_it");
    await database.create();
    places = database.collection("places");
    await places.create();
    await database.collection("typed").create();
    await places.saveAll([
        {
            _id: "ny",
            openedAt: LocalDateTime.of(2024, 3, 1, 9, 0),
            opensAt: LocalTime.of(9, 0),
            location: NEW_YORK,
            embedding: Vector.of(1, 0, 0),
        },
        {
            _id: "nwk",
            openedAt: LocalDateTime.of(2023, 12, 31, 23, 59, 59),
            opensAt: LocalTime.of(10, 30),
            location: NEWARK,
            embedding: Vector.of(0.9, 0.1, 0),
        },
        {
            _id: "lima",
            openedAt: LocalDateTime.of(2024, 1, 1, 10, 0),
            opensAt: LocalTime.of(8, 15),
            location: LIMA,
            embedding: Vector.of(0, 1, 0),
            stops: [GeoPoint.of(1, 2), GeoPoint.of(3, 4)],
        },
    ]);
});

describe("custom value types", () => {
    test("every custom type round-trips, also through a typed collection", async () => {
        const lima = await places.findById("lima");
        expect(lima?.openedAt).toEqual(LocalDateTime.of(2024, 1, 1, 10, 0));
        expect(lima?.opensAt).toEqual(LocalTime.of(8, 15));
        expect(lima?.location).toEqual(LIMA);
        expect((lima?.embedding as Vector).equals(Vector.of(0, 1, 0))).toBe(true);
        expect(lima?.stops).toEqual([GeoPoint.of(1, 2), GeoPoint.of(3, 4)]);
        const typed = it.client.database("custom_it").collection<Place>("typed");
        const place: Place = {
            _id: "p1",
            openedAt: LocalDateTime.of(2024, 5, 5, 5, 5, 5, 123_000_000),
            opensAt: LocalTime.of(6, 7),
            location: NEW_YORK,
            embedding: Vector.of(0.25, 0.5, -0),
        };
        await typed.save(place);
        const read = await typed.findById("p1");
        expect(read?.openedAt.equals(place.openedAt)).toBe(true);
        expect(read?.embedding.equals(place.embedding)).toBe(true);
        expect(read?.location).toEqual(NEW_YORK);
    });

    test("datetime and time compare and sort chronologically", async () => {
        expect(
            await sameWithAndWithoutIndex(places, "openedAt", async () =>
                sortedIds(
                    await places
                        .query()
                        .filter(field("openedAt").gte(LocalDateTime.of(2024, 1, 1, 10, 0)))
                        .toArray(),
                ),
            ),
        ).toEqual(["lima", "ny"]);
        expect(
            await sameWithAndWithoutIndex(places, "openedAt", async () =>
                ids(
                    await places
                        .query()
                        .filter(field("openedAt").eq(LocalDateTime.of(2024, 1, 1, 10, 0)))
                        .toArray(),
                ),
            ),
        ).toEqual(["lima"]);
        expect(
            await sameWithAndWithoutIndex(places, "openedAt", async () =>
                ids(await places.query().sort("openedAt", Order.ASC).toArray()),
            ),
        ).toEqual(["nwk", "lima", "ny"]);
        expect(
            ids(
                await places
                    .query()
                    .filter(field("opensAt").gt(LocalTime.of(9, 0)))
                    .toArray(),
            ),
        ).toEqual(["nwk"]);
    });

    test("geo distance, within and in", async () => {
        expect(
            await sameWithAndWithoutIndex(places, "location", async () =>
                sortedIds(
                    await places
                        .query()
                        .filter(distance("location", NEW_YORK, GeoDistanceComparator.SMALLER_THAN, 20_000))
                        .toArray(),
                ),
            ),
        ).toEqual(["nwk", "ny"]);
        expect(
            sortedIds(
                await places
                    .query()
                    .filter(distance("location", NEW_YORK, GeoDistanceComparator.GREATER_THAN, 20_000))
                    .toArray(),
            ),
        ).toEqual(["lima"]);
        const count = (comparator: GeoDistanceComparator) =>
            places
                .query()
                .filter(distance("location", NEW_YORK, comparator, 0))
                .count();
        expect(await count(GeoDistanceComparator.GREATER_THAN_EQUALS)).toBe(3);
        expect(await count(GeoDistanceComparator.SMALLER_THAN_EQUALS)).toBe(1);
        expect(await count(GeoDistanceComparator.EQUALS)).toBe(1);
        const square = within(
            "location",
            GeoPoint.of(40.6, -74.1),
            GeoPoint.of(40.8, -74.1),
            GeoPoint.of(40.8, -73.9),
            GeoPoint.of(40.6, -73.9),
        );
        expect(ids(await places.query().filter(square).toArray())).toEqual(["ny"]);
        expect(
            await sameWithAndWithoutIndex(places, "location", async () =>
                sortedIds(await places.query().filter(field("location").in(NEW_YORK, LIMA)).toArray()),
            ),
        ).toEqual(["lima", "ny"]);
    });

    test("vector nearest and equality", async () => {
        expect(
            ids(
                await places
                    .query()
                    .filter(nearest("embedding", Vector.of(1, 0, 0), 2).exact())
                    .toArray(),
            ),
        ).toEqual(["ny", "nwk"]);
        expect(
            (
                await places
                    .query()
                    .filter(nearest("embedding", Vector.of(1, 0, 0), 2))
                    .toArray()
            ).length,
        ).toBeLessThanOrEqual(2);
        expect(
            ids(
                await places
                    .query()
                    .filter(field("embedding").eq(Vector.of(0, 1, 0)))
                    .toArray(),
            ),
        ).toEqual(["lima"]);
    });

    test("casts to custom types and refusals", async () => {
        await places.save({ _id: "raw", text: "2024-01-01T10:00", bad: "x" });
        const [cast] = await places
            .query()
            .filter(field("_id").eq("raw"))
            .map(
                set("typed", MidOps.cast("text", CustomType.DATETIME)),
                set("broken", MidOps.cast("bad", CustomType.GEO)),
            )
            .toArray();
        expect(cast?.typed).toEqual(LocalDateTime.of(2024, 1, 1, 10, 0));
        expect(cast?.broken ?? null).toBeNull();
        await places.delete("raw");
        await expect(places.save({ _id: "v", embedding: Vector.of(1, 2, 3, 4, 5, 6, 7, 8, 9) })).rejects.toMatchObject({
            rawCode: "400-43",
        });
        await expect(places.save({ note: "#geo(1,2)" })).rejects.toBeInstanceOf(MappingError);
        expect(await places.query().count()).toBe(3);
    });

    test("a Date is stored through the configured temporal zone", async () => {
        const zoned = await LwnrdbClient.connect(it.settings({ temporalZone: "America/Lima" }));
        try {
            const instant = new Date(Date.UTC(2024, 0, 1, 15, 0));
            await zoned
                .database("custom_it")
                .collection<{ _id: string; at: Date }>("typed")
                .save({ _id: "zoned", at: instant });
            const read = await zoned.database("custom_it").collection("typed").findById("zoned");
            expect(read?.at).toEqual(LocalDateTime.of(2024, 1, 1, 10, 0));
            expect((read?.at as LocalDateTime).toDate("America/Lima").getTime()).toBe(instant.getTime());
        } finally {
            await zoned.close();
        }
    });
});
