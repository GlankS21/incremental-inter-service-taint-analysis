import { describe, expect, it } from "vitest";
import { lastPageRedirect, pageHref } from "./pagination";

describe("pageHref", () => {
  it("keeps the filters and writes 1-based pages", () => {
    expect(pageHref("/orders", { status: "PAID", keyword: undefined }, 2)).toBe("/orders?status=PAID&page=3");
    expect(pageHref("/orders", { status: "PAID" }, 0)).toBe("/orders?status=PAID");
    expect(pageHref("/orders", {}, 0)).toBe("/orders");
  });
});

describe("lastPageRedirect", () => {
  it("sends a page past the end to the last page", () => {
    expect(lastPageRedirect(998, 3, "/orders", { status: "PAID" })).toBe("/orders?status=PAID&page=3");
    expect(lastPageRedirect(3, 3, "/admin/products", {})).toBe("/admin/products?page=3");
  });

  it("keeps pages in range and empty lists", () => {
    expect(lastPageRedirect(2, 3, "/orders", {})).toBeNull();
    expect(lastPageRedirect(0, 1, "/orders", {})).toBeNull();
    expect(lastPageRedirect(5, 0, "/orders", {})).toBeNull();
  });
});
