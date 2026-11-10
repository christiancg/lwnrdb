import { beforeAll, describe, expect, test } from "vitest";
import {
    type Collection,
    type Document,
    field,
    ListenEndedError,
    type ListenHandler,
    type ListenUpdate,
    type Stored,
    type Subscription,
} from "../../src/index.js";
import { serverIt } from "./server-it.js";

type Row = Stored<Document>;
type Event = unknown;

class Events implements ListenHandler<Row> {
    readonly #queue: Event[] = [];
    readonly #waiters: ((event: Event) => void)[] = [];

    onUpdate(update: ListenUpdate<Row>): void {
        this.#push(update);
    }

    onEnded(reason: ListenEndedError): void {
        this.#push(reason);
    }

    onError(error: unknown): void {
        this.#push(error);
    }

    next(timeoutMs: number): Promise<Event> {
        const queued = this.#queue.shift();
        if (queued !== undefined) {
            return Promise.resolve(queued);
        }
        return new Promise((resolve) => {
            const waiter = (event: Event): void => {
                clearTimeout(timer);
                resolve(event);
            };
            const timer = setTimeout(() => {
                this.#waiters.splice(this.#waiters.indexOf(waiter), 1);
                resolve(undefined);
            }, timeoutMs);
            this.#waiters.push(waiter);
        });
    }

    async update(): Promise<ListenUpdate<Row>> {
        const event = await this.next(10_000);
        expect(event).toHaveProperty("initial");
        return event as ListenUpdate<Row>;
    }

    #push(event: Event): void {
        const waiter = this.#waiters.shift();
        if (waiter === undefined) {
            this.#queue.push(event);
        } else {
            waiter(event);
        }
    }
}

const it = serverIt({ maxListensPerConnection: "2" });
let orders: Collection<Document, Stored<Document>>;

beforeAll(async () => {
    await it.client.database("listen_it").create();
    orders = it.client.database("listen_it").collection("orders");
    await orders.create();
});

describe("live queries", () => {
    test("a matching write pushes and a closed subscription stops", async () => {
        const events = new Events();
        const subscription = await orders.listen(orders.query().filter(field("status").eq("pending")), events);
        expect((await events.update()).initial).toBe(true);
        await orders.save({ _id: "p1", status: "pending" });
        const pushed = await events.update();
        expect(pushed.initial).toBe(false);
        expect(pushed.results[0]?._id).toBe("p1");
        await orders.save({ _id: "x1", status: "done" });
        expect(await events.next(1000)).toBeUndefined();
        await subscription.close();
        await orders.save({ _id: "p2", status: "pending" });
        expect(await events.next(1000)).toBeUndefined();
    });

    test("updates arrive in write order", async () => {
        const events = new Events();
        const subscription = await orders.listen(orders.query().filter(field("status").eq("burst")), events);
        await events.update();
        for (let i = 0; i < 5; i++) {
            await orders.save({ _id: `burst-${i}`, status: "burst" });
        }
        let last = 0;
        while (last < 5) {
            const update = await events.update();
            expect(update.results.length).toBeGreaterThanOrEqual(last);
            last = update.results.length;
        }
        await subscription.close();
    });

    test("dropping the collection ends the listen", async () => {
        const doomed = it.client.database("listen_it").collection("doomed");
        await doomed.create();
        const events = new Events();
        const subscription = await doomed.listen(events);
        await events.update();
        await doomed.drop();
        const ended = await events.next(10_000);
        expect(ended).toBeInstanceOf(ListenEndedError);
        expect((ended as ListenEndedError).rawCode).toBe("410-1");
        expect(subscription.isActive).toBe(false);
        await subscription.close();
    });

    test("listens past the per-connection limit spread over connections", async () => {
        const subscriptions: Subscription[] = [];
        const handlers: Events[] = [];
        for (let i = 0; i < 5; i++) {
            const events = new Events();
            handlers.push(events);
            subscriptions.push(await orders.listen(orders.query().filter(field("status").eq("fanout")), events));
            await events.update();
        }
        await orders.save({ _id: "f1", status: "fanout" });
        for (const events of handlers) {
            expect((await events.update()).results.map((row) => row._id)).toEqual(["f1"]);
        }
        await Promise.all(subscriptions.map((subscription) => subscription.close()));
    });
});
