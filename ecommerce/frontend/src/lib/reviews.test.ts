import { describe, expect, it } from "vitest";
import { badgeText, ratingAfterKey } from "./reviews";

describe("ratingAfterKey", () => {
  it("moves one star with the arrows, within 1 to 5", () => {
    expect(ratingAfterKey(3, "ArrowRight")).toBe(4);
    expect(ratingAfterKey(3, "ArrowUp")).toBe(4);
    expect(ratingAfterKey(3, "ArrowLeft")).toBe(2);
    expect(ratingAfterKey(3, "ArrowDown")).toBe(2);
    expect(ratingAfterKey(5, "ArrowRight")).toBe(5);
    expect(ratingAfterKey(1, "ArrowLeft")).toBe(1);
  });

  it("chooses one star from no rating", () => {
    expect(ratingAfterKey(0, "ArrowRight")).toBe(1);
    expect(ratingAfterKey(0, "ArrowLeft")).toBe(1);
  });

  it("jumps with Home, End and the digits", () => {
    expect(ratingAfterKey(3, "Home")).toBe(1);
    expect(ratingAfterKey(3, "End")).toBe(5);
    expect(ratingAfterKey(0, "4")).toBe(4);
  });

  it("ignores other keys", () => {
    expect(ratingAfterKey(3, "Tab")).toBeNull();
    expect(ratingAfterKey(3, "6")).toBeNull();
    expect(ratingAfterKey(3, "0")).toBeNull();
  });
});

describe("badgeText", () => {
  it("shows nothing without pending reviews", () => {
    expect(badgeText(0)).toBeNull();
    expect(badgeText(null)).toBeNull();
    expect(badgeText(undefined)).toBeNull();
  });

  it("caps the count at 99+", () => {
    expect(badgeText(7)).toBe("7");
    expect(badgeText(99)).toBe("99");
    expect(badgeText(100)).toBe("99+");
  });
});
