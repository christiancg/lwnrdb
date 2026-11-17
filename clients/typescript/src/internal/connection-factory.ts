import { isIP, connect as connectPlain, type Socket } from "node:net";
import { checkServerIdentity, connect as connectTls } from "node:tls";
import { ConnectionLostError } from "../errors/errors.js";
import type { ResolvedSettings, ServerAddress } from "../settings.js";
import type { TlsSettings } from "../tls-settings.js";
import type { RequestEncoder } from "./request-encoder.js";
import { check } from "./response-decoder.js";
import { WireConnection } from "./wire-connection.js";

export type SocketOpener = (address: ServerAddress, settings: ResolvedSettings) => Promise<Socket>;

const INSECURE_WARNING = "LWNRDB TLS certificate and hostname checks are disabled; use this only in development";

function tlsSocket(address: ServerAddress, tls: TlsSettings): Socket {
    const insecure = tls.insecure === true;
    const servername = tls.servername ?? (isIP(address.host) === 0 ? address.host : undefined);
    return connectTls({
        host: address.host,
        port: address.port,
        ...(tls.ca === undefined ? {} : { ca: tls.ca as string | Buffer | (string | Buffer)[] }),
        ...(servername === undefined ? {} : { servername }),
        rejectUnauthorized: !insecure,
        checkServerIdentity: insecure ? () => undefined : checkServerIdentity,
    });
}

export const openSocket: SocketOpener = (address, settings) =>
    new Promise((resolve, reject) => {
        const socket =
            settings.tls === undefined
                ? connectPlain({ host: address.host, port: address.port })
                : tlsSocket(address, settings.tls);
        const fail = (error: Error): void => {
            clearTimeout(timer);
            socket.destroy();
            reject(error);
        };
        const timer = setTimeout(() => {
            fail(new Error(`no connection within ${settings.connectTimeoutMs} ms`));
        }, settings.connectTimeoutMs);
        socket.once("error", fail);
        socket.once(settings.tls === undefined ? "connect" : "secureConnect", () => {
            clearTimeout(timer);
            socket.removeListener("error", fail);
            socket.setNoDelay(true);
            socket.setKeepAlive(true);
            resolve(socket);
        });
    });

export class ConnectionFactory {
    readonly settings: ResolvedSettings;
    readonly #encoder: RequestEncoder;
    readonly #opener: SocketOpener;
    #nextHost = 0;

    constructor(settings: ResolvedSettings, encoder: RequestEncoder, opener: SocketOpener = openSocket) {
        this.settings = settings;
        this.#encoder = encoder;
        this.#opener = opener;
        if (settings.tls?.insecure === true) {
            process.emitWarning(INSECURE_WARNING, { code: "LWNRDB_INSECURE_TLS" });
        }
    }

    async open(): Promise<WireConnection> {
        const hosts = this.settings.hosts;
        let last: ConnectionLostError | undefined;
        for (let remaining = hosts.length; remaining > 0; remaining--) {
            const address = hosts[this.#nextHost++ % hosts.length];
            if (address === undefined) {
                continue;
            }
            let socket: Socket;
            try {
                socket = await this.#opener(address, this.settings);
            } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                last = new ConnectionLostError(`could not connect to ${address.host}:${address.port}: ${message}`, {
                    cause: error,
                });
                continue;
            }
            return this.#authenticated(new WireConnection(socket, this.settings.maxReplyBytes));
        }
        throw last ?? new ConnectionLostError("no host to connect to");
    }

    async #authenticated(connection: WireConnection): Promise<WireConnection> {
        const credentials = this.settings.credentials;
        if (credentials === undefined) {
            return connection;
        }
        try {
            check(
                await connection.request(
                    this.#encoder.authenticate(credentials.username, credentials.password),
                    this.settings.requestTimeoutMs,
                ),
            );
            return connection;
        } catch (error) {
            connection.close();
            throw error;
        }
    }
}
