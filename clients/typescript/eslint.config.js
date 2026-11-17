import js from "@eslint/js";
import { defineConfig } from "eslint/config";
import tseslint from "typescript-eslint";

export default defineConfig(
    { ignores: ["dist/**", "coverage/**", "node_modules/**", "consumers/**"] },
    js.configs.recommended,
    tseslint.configs.strictTypeChecked,
    tseslint.configs.stylisticTypeChecked,
    {
        languageOptions: {
            parserOptions: {
                projectService: { allowDefaultProject: ["eslint.config.js"] },
                tsconfigRootDir: import.meta.dirname,
            },
        },
        rules: {
            "max-lines": ["error", { max: 500 }],
            "max-lines-per-function": ["error", { max: 200 }],
            "no-console": "error",
            "@typescript-eslint/no-floating-promises": "error",
            "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true, allowBoolean: true }],
        },
    },
    {
        files: ["eslint.config.js"],
        extends: [tseslint.configs.disableTypeChecked],
    },
);
