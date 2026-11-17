const NAME = /^[A-Za-z0-9_-]{3,64}$/;
const ID = /^[A-Za-z0-9_-]{1,64}$/;
const NAME_RULE = "3-64 characters from A-Z a-z 0-9 _ -";
const ID_RULE = "1-64 characters from A-Z a-z 0-9 _ -";

function check(value: unknown, pattern: RegExp, label: string, rule: string): string {
    if (typeof value !== "string" || !pattern.test(value)) {
        throw new TypeError(`invalid ${label} '${String(value)}': it must be ${rule}`);
    }
    return value;
}

export function databaseName(name: unknown): string {
    return check(name, NAME, "database name", NAME_RULE);
}

export function collectionName(name: unknown): string {
    return check(name, NAME, "collection name", NAME_RULE);
}

export function documentId(id: unknown): string {
    return check(id, ID, "_id", ID_RULE);
}
