export const GeoDistanceComparator = {
    EQUALS: "EQUALS",
    GREATER_THAN: "GREATER_THAN",
    GREATER_THAN_EQUALS: "GREATER_THAN_EQUALS",
    SMALLER_THAN: "SMALLER_THAN",
    SMALLER_THAN_EQUALS: "SMALLER_THAN_EQUALS",
} as const;

export type GeoDistanceComparator = (typeof GeoDistanceComparator)[keyof typeof GeoDistanceComparator];
