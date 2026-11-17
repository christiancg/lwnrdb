import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import ts from "typescript";

const ROOT = join(import.meta.dirname, "..");
const DIST = join(ROOT, "dist");
const APIDOCS = join(ROOT, "apidocs");
const ENTRY = join(DIST, "index.d.ts");
const SECTION = /^## (.+)$/;

interface ApiDoc {
    readonly summary: string;
    readonly members: Map<string, string>;
}

interface Insertion {
    readonly position: number;
    readonly text: string;
}

interface Target {
    readonly owner: string;
    readonly member: string | undefined;
    readonly node: ts.Node;
}

function parseApiDoc(text: string): ApiDoc {
    const summary: string[] = [];
    const sections = new Map<string, string[]>();
    let current = summary;
    for (const line of text.split("\n")) {
        const heading = SECTION.exec(line);
        if (heading?.[1] !== undefined) {
            current = [];
            sections.set(heading[1].trim(), current);
        } else if (!line.startsWith("# ")) {
            current.push(line);
        }
    }
    const members = new Map([...sections].map(([name, lines]) => [name, lines.join("\n").trim()]));
    return { summary: summary.join("\n").trim(), members };
}

function readApiDocs(): Map<string, ApiDoc> {
    const docs = new Map<string, ApiDoc>();
    for (const file of readdirSync(APIDOCS).filter((entry) => entry.endsWith(".md"))) {
        docs.set(basename(file, ".md"), parseApiDoc(readFileSync(join(APIDOCS, file), "utf8")));
    }
    return docs;
}

function statementOf(declaration: ts.Declaration): ts.Node {
    return ts.isVariableDeclaration(declaration) ? declaration.parent.parent : declaration;
}

function memberName(member: ts.ClassElement | ts.TypeElement): string | undefined {
    if (ts.isConstructorDeclaration(member) || member.name === undefined) {
        return undefined;
    }
    if (ts.isPrivateIdentifier(member.name)) {
        return undefined;
    }
    const modifiers = ts.canHaveModifiers(member) ? (ts.getModifiers(member) ?? []) : [];
    if (modifiers.some((modifier) => modifier.kind === ts.SyntaxKind.PrivateKeyword)) {
        return undefined;
    }
    if (ts.isComputedPropertyName(member.name)) {
        return member.name.expression.getText();
    }
    return member.name.getText();
}

function membersOf(node: ts.Node): readonly (ts.ClassElement | ts.TypeElement)[] {
    if (ts.isClassDeclaration(node) || ts.isInterfaceDeclaration(node)) {
        return node.members;
    }
    return [];
}

function targetsOf(checker: ts.TypeChecker, entry: ts.SourceFile): Target[] {
    const module = checker.getSymbolAtLocation(entry);
    if (module === undefined) {
        throw new Error(`${ENTRY} has no exports`);
    }
    const targets: Target[] = [];
    for (const exported of checker.getExportsOfModule(module)) {
        const symbol = exported.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(exported) : exported;
        if (symbol.flags & ts.SymbolFlags.ValueModule) {
            for (const inner of checker.getExportsOfModule(symbol)) {
                inner.declarations?.forEach((declaration) => {
                    targets.push({ owner: exported.name, member: inner.name, node: statementOf(declaration) });
                });
            }
            continue;
        }
        for (const declaration of symbol.declarations ?? []) {
            const node = statementOf(declaration);
            targets.push({ owner: exported.name, member: undefined, node });
            for (const member of membersOf(declaration)) {
                const name = memberName(member);
                if (name !== undefined) {
                    targets.push({ owner: exported.name, member: name, node: member });
                }
            }
        }
    }
    return targets;
}

function jsDoc(text: string, indent: string): string {
    const lines = text.split("\n").map((line) => (line === "" ? `${indent} *` : `${indent} * ${line}`));
    return `/**\n${lines.join("\n")}\n${indent} */\n${indent}`;
}

function indentOf(source: ts.SourceFile, position: number): string {
    const lineStart = source.getLineStarts()[source.getLineAndCharacterOfPosition(position).line] ?? 0;
    return source.text.slice(lineStart, position);
}

function main(): void {
    if (!existsSync(ENTRY)) {
        throw new Error(`${ENTRY} is missing; run tsc first`);
    }
    const docs = readApiDocs();
    const program = ts.createProgram([ENTRY], { noEmit: true, skipLibCheck: true });
    const checker = program.getTypeChecker();
    const entry = program.getSourceFile(ENTRY);
    if (entry === undefined) {
        throw new Error(`could not read ${ENTRY}`);
    }
    const problems: string[] = [];
    const used = new Set<string>();
    const insertions = new Map<ts.SourceFile, Insertion[]>();
    const targets = targetsOf(checker, entry);
    const owners = new Set(targets.map((target) => target.owner));
    for (const target of targets) {
        const doc = docs.get(target.owner);
        const key = target.member === undefined ? target.owner : `${target.owner}#${target.member}`;
        const text = target.member === undefined ? doc?.summary : doc?.members.get(target.member);
        if (text === undefined || text === "") {
            const missing = target.member === undefined ? "summary" : `## ${target.member} section`;
            problems.push(`apidocs/${target.owner}.md has no ${missing}`);
            continue;
        }
        used.add(key);
        const source = target.node.getSourceFile();
        const position = target.node.getStart(source);
        const list = insertions.get(source) ?? [];
        list.push({ position, text: jsDoc(text, indentOf(source, position)) });
        insertions.set(source, list);
    }
    for (const [owner, doc] of docs) {
        if (!owners.has(owner)) {
            problems.push(`apidocs/${owner}.md documents nothing the package exports`);
        }
        for (const [member, text] of doc.members) {
            if (text !== "" && !used.has(`${owner}#${member}`)) {
                problems.push(`apidocs/${owner}.md has a ## ${member} section for a member that does not exist`);
            }
        }
    }
    if (problems.length > 0) {
        process.stderr.write(`${[...new Set(problems)].sort().join("\n")}\n`);
        process.exitCode = 1;
        return;
    }
    for (const [source, list] of insertions) {
        let text = source.text;
        const unique = [...new Map(list.map((insertion) => [insertion.position, insertion])).values()];
        for (const insertion of unique.sort((left, right) => right.position - left.position)) {
            text = text.slice(0, insertion.position) + insertion.text + text.slice(insertion.position);
        }
        writeFileSync(source.fileName, text);
    }
    process.stdout.write(`documented ${used.size} declarations from ${docs.size} apidocs files\n`);
}

main();
