import { Admin, type Collection, Database, type Document, type Stored } from "../../src/index.js";
import type { ClientContext } from "../../src/internal/client-context.js";
import { RecordingChannel, recordingContext } from "../support/recording-channel.js";

export class Recorder {
    readonly channel = new RecordingChannel();
    readonly context: ClientContext = recordingContext(this.channel);
    readonly shop = new Database(this.context, "shop");
    readonly orders: Collection<Document, Stored<Document>> = this.shop.collection("orders");
    readonly admin = new Admin(this.context);
    readonly encoder = this.context.encoder;
    readonly scripts = this.context.scripts;

    async raw(request: ReturnType<RecordingChannel["last"]>): Promise<void> {
        await this.channel.send("SERVER_INFO", request);
    }
}

export type FixtureCall = (recorder: Recorder) => Promise<unknown>;
