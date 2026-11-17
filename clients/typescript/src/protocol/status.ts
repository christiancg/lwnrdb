export const Status = {
    OK: "OK",
    ERROR: "ERROR",
    NOT_FOUND: "NOT_FOUND",
    NO_OPERATION: "NO_OPERATION",
    UNAUTHENTICATED: "UNAUTHENTICATED",
    FORBIDDEN: "FORBIDDEN",
} as const;

export type Status = (typeof Status)[keyof typeof Status];
