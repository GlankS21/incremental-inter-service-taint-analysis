import { describe, expect, it } from "vitest";
import { wrapFocus } from "./focus-trap";

describe("wrapFocus", () => {
  it("wraps from the last element to the first and back", () => {
    expect(wrapFocus(3, 2, false)).toBe(0);
    expect(wrapFocus(3, 0, true)).toBe(2);
  });

  it("lets the browser move the focus inside the dialog", () => {
    expect(wrapFocus(3, 0, false)).toBeNull();
    expect(wrapFocus(3, 1, true)).toBeNull();
  });

  it("brings the focus back into the dialog", () => {
    expect(wrapFocus(3, -1, false)).toBe(0);
    expect(wrapFocus(3, -1, true)).toBe(2);
  });

  it("does nothing without focusable elements", () => {
    expect(wrapFocus(0, -1, false)).toBeNull();
  });
});
