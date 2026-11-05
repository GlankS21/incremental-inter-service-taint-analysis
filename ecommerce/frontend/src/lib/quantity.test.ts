import { describe, expect, it } from "vitest";
import { clampQuantity, quantityDraft } from "./quantity";

describe("quantityDraft", () => {
  it("lets the field be cleared while typing", () => {
    expect(quantityDraft("")).toBe("");
    expect(quantityDraft("12")).toBe("12");
  });

  it("keeps digits only", () => {
    expect(quantityDraft("-3")).toBe("3");
    expect(quantityDraft("1e2")).toBe("12");
  });
});

describe("clampQuantity", () => {
  it("clamps the typed value on blur and submit", () => {
    expect(clampQuantity("5", 10)).toBe(5);
    expect(clampQuantity("50", 10)).toBe(10);
    expect(clampQuantity("0", 10)).toBe(1);
  });

  it("falls back to one for an empty field", () => {
    expect(clampQuantity("", 10)).toBe(1);
  });
});
