export interface TlsSettings {
    readonly ca?: string | Buffer | readonly (string | Buffer)[];
    readonly servername?: string;
    readonly insecure?: boolean;
}
