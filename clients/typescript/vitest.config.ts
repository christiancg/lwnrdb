import { defineConfig } from "vitest/config";

export default defineConfig({
    test: {
        include: ["test/unit/**/*.test.ts", "test/fixtures/**/*.test.ts", "test/structure/**/*.test.ts"],
        coverage: {
            provider: "v8",
            include: ["src/**/*.ts"],
            reporter: ["text-summary", "html", "json-summary"],
            thresholds: { lines: 95, statements: 95, functions: 95, branches: 90 },
        },
    },
});
