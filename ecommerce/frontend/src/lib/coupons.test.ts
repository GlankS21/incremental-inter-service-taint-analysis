import { describe, expect, it } from "vitest";
import { couponState, fromDateTimeLocal, toDateTimeLocal } from "./coupons";
import type { Coupon } from "./types";

const NOW = new Date("2026-10-21T12:00:00Z");

function coupon(overrides: Partial<Coupon> = {}): Coupon {
  return {
    id: 1,
    code: "WELCOME10",
    description: null,
    discountType: "PERCENTAGE",
    discountValue: 10,
    minOrderAmount: null,
    maxDiscountAmount: null,
    startsAt: null,
    expiresAt: null,
    usageLimit: null,
    usedCount: 0,
    perUserLimit: null,
    active: true,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

describe("couponState", () => {
  it("is active without limits", () => {
    expect(couponState(coupon(), NOW)).toBe("active");
  });

  it("reports a disabled coupon first", () => {
    expect(couponState(coupon({ active: false, expiresAt: "2026-01-01T00:00:00Z" }), NOW)).toBe("inactive");
  });

  it("knows scheduled and expired periods", () => {
    expect(couponState(coupon({ startsAt: "2026-11-01T00:00:00Z" }), NOW)).toBe("scheduled");
    expect(couponState(coupon({ expiresAt: "2026-10-21T12:00:00Z" }), NOW)).toBe("expired");
    expect(couponState(coupon({ startsAt: "2026-10-01T00:00:00Z", expiresAt: "2026-12-01T00:00:00Z" }), NOW)).toBe("active");
  });

  it("is exhausted when the usage limit is reached", () => {
    expect(couponState(coupon({ usageLimit: 5, usedCount: 5 }), NOW)).toBe("exhausted");
    expect(couponState(coupon({ usageLimit: 5, usedCount: 4 }), NOW)).toBe("active");
  });
});

describe("datetime-local conversion", () => {
  it("round-trips through the browser's time zone", () => {
    const local = toDateTimeLocal("2026-10-21T09:30:00.000Z");
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(fromDateTimeLocal(local)).toBe("2026-10-21T09:30:00.000Z");
  });

  it("maps empty values to null and back", () => {
    expect(toDateTimeLocal(null)).toBe("");
    expect(fromDateTimeLocal("")).toBeNull();
    expect(fromDateTimeLocal("not a date")).toBeNull();
  });

  it("uses the shop's time zone whatever the time zone of the browser", () => {
    const original = process.env.TZ;
    try {
      for (const zone of ["UTC", "America/New_York", "Asia/Tokyo"]) {
        process.env.TZ = zone;
        expect(toDateTimeLocal("2026-11-01T00:00:00Z")).toBe("2026-11-01T03:00");
        expect(fromDateTimeLocal("2026-11-01T03:00")).toBe("2026-11-01T00:00:00.000Z");
      }
    } finally {
      if (original === undefined) {
        delete process.env.TZ;
      } else {
        process.env.TZ = original;
      }
    }
  });
});
