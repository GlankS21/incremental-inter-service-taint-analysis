import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SRC = path.resolve(import.meta.dirname);

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return sourceFiles(full);
    }
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [full] : [];
  });
}

const isClientModule = (source: string) => /^\s*["']use client["']/.test(source);

/** "@/components/x" → absolute path of the module file, when it exists. */
function resolveAlias(specifier: string, files: Set<string>): string | undefined {
  if (!specifier.startsWith("@/")) {
    return undefined;
  }
  const base = path.join(SRC, specifier.slice(2));
  return [`${base}.tsx`, `${base}.ts`, path.join(base, "index.tsx"), path.join(base, "index.ts")]
    .find((candidate) => files.has(candidate));
}

describe("client module boundary", () => {
  it("never imports plain values from a client module into server code", () => {
    // In a server component every export of a "use client" module is a client reference, not the value:
    // `${REVIEWS_PAGE_SIZE}` used to throw there, and the product page showed its reviews as failed.
    const files = sourceFiles(SRC);
    const fileSet = new Set(files);
    const sources = new Map(files.map((file) => [file, readFileSync(file, "utf8")]));
    const violations: string[] = [];

    for (const [file, source] of sources) {
      if (isClientModule(source)) {
        continue;
      }
      for (const match of source.matchAll(/import\s+(?!type\b)\{([^}]*)\}\s+from\s+["']([^"']+)["']/g)) {
        const target = resolveAlias(match[2], fileSet);
        if (!target || !isClientModule(sources.get(target) ?? "")) {
          continue;
        }
        const values = match[1]
          .split(",")
          .map((name) => name.trim())
          .filter((name) => name && !name.startsWith("type "))
          .map((name) => name.split(/\s+as\s+/)[0]);
        // Components (PascalCase) are fine: they render on the client. Anything else is not.
        values
          .filter((name) => !/^[A-Z][a-z]/.test(name))
          .forEach((name) => violations.push(`${path.relative(SRC, file)} imports ${name} from ${match[2]}`));
      }
    }

    expect(violations).toEqual([]);
  });
});
