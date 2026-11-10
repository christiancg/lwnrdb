const NEWLINE = 0x0a;
const CARRIAGE_RETURN = 0x0d;

export type LineEvent = { readonly tooLarge: false; readonly text: string } | { readonly tooLarge: true };

export class LineReader {
    readonly #maxLineBytes: number;
    #pending: Buffer[] = [];
    #pendingBytes = 0;
    #tooLarge = false;

    constructor(maxLineBytes: number) {
        this.#maxLineBytes = maxLineBytes;
    }

    push(chunk: Buffer): LineEvent[] {
        const events: LineEvent[] = [];
        let start = 0;
        let newline = chunk.indexOf(NEWLINE, start);
        while (newline !== -1) {
            this.#append(chunk.subarray(start, newline));
            events.push(this.#takeLine());
            start = newline + 1;
            newline = chunk.indexOf(NEWLINE, start);
        }
        this.#append(chunk.subarray(start));
        return events;
    }

    #append(segment: Buffer): void {
        if (segment.length === 0 || this.#tooLarge) {
            return;
        }
        if (this.#pendingBytes + segment.length > this.#maxLineBytes) {
            this.#tooLarge = true;
            this.#pending = [];
            this.#pendingBytes = 0;
            return;
        }
        this.#pending.push(segment);
        this.#pendingBytes += segment.length;
    }

    #takeLine(): LineEvent {
        if (this.#tooLarge) {
            this.#tooLarge = false;
            return { tooLarge: true };
        }
        let line = this.#pending.length === 1 ? (this.#pending[0] ?? Buffer.alloc(0)) : Buffer.concat(this.#pending);
        this.#pending = [];
        this.#pendingBytes = 0;
        if (line.length > 0 && line[line.length - 1] === CARRIAGE_RETURN) {
            line = line.subarray(0, line.length - 1);
        }
        return { tooLarge: false, text: line.toString("utf8") };
    }
}
