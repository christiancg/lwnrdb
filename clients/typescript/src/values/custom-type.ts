export const CustomType = {
    DATETIME: "datetime",
    TIME: "time",
    GEO: "geo",
    VECTOR: "vector",
} as const;

export type CustomType = (typeof CustomType)[keyof typeof CustomType];
