import { describe, expect, it } from "vitest";
import { loginUrl, safeNext } from "./safe-redirect";

describe("safeNext", () => {
  it("keeps paths of this site", () => {
    expect(safeNext("/orders/123?placed=1")).toBe("/orders/123?placed=1");
    expect(safeNext("/products?keyword=iphone")).toBe("/products?keyword=iphone");
  });

  it("rejects other sites and scheme tricks", () => {
    expect(safeNext("https://evil.example")).toBe("/");
    expect(safeNext("//evil.example")).toBe("/");
    expect(safeNext("/\\evil.example")).toBe("/");
    expect(safeNext("javascript:alert(1)")).toBe("/");
    expect(safeNext("/\tfoo")).toBe("/");
  });

  it("avoids loops through the sign-in pages", () => {
    expect(safeNext("/login?next=/cart")).toBe("/");
    expect(safeNext("/register")).toBe("/");
    expect(safeNext("/login-help")).toBe("/login-help");
  });

  it("falls back when absent", () => {
    expect(safeNext(undefined, "/account")).toBe("/account");
  });
});

describe("loginUrl", () => {
  it("brings the user back to the current page after signing in again", () => {
    expect(loginUrl("/orders/123?placed=1")).toBe("/login?next=%2Forders%2F123%3Fplaced%3D1");
  });

  it("does not loop through the sign-in page", () => {
    expect(loginUrl("/login?next=/cart")).toBe("/login");
    expect(loginUrl("/")).toBe("/login");
  });
});
