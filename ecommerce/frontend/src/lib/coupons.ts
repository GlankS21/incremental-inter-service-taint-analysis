import type { Coupon } from "./types";
import type { Tone } from "./format";
import { TIME_ZONE } from "@/i18n/config";

export type CouponState = "active" | "inactive" | "scheduled" | "expired" | "exhausted";

export const COUPON_STATE_TONE: Record<CouponState, Tone> = {
  active: "success",
  inactive: "neutral",
  scheduled: "info",
  expired: "danger",
  exhausted: "warning",
};

/** Whether customers can use the coupon right now, and if not, why. */
export function couponState(coupon: Coupon, now: Date = new Date()): CouponState {
  if (!coupon.active) {
    return "inactive";
  }
  if (coupon.startsAt && new Date(coupon.startsAt) > now) {
    return "scheduled";
  }
  if (coupon.expiresAt && new Date(coupon.expiresAt) <= now) {
    return "expired";
  }
  if (coupon.usageLimit !== null && coupon.usedCount >= coupon.usageLimit) {
    return "exhausted";
  }
  return "active";
}

// Wall-clock fields in the shop's time zone; "en-US" gives plain digits whatever the user's locale
const SHOP_CLOCK = new Intl.DateTimeFormat("en-US", {
  timeZone: TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
});

function shopClock(epochMs: number): Record<"year" | "month" | "day" | "hour" | "minute" | "second", number> {
  const parts = Object.fromEntries(
    SHOP_CLOCK.formatToParts(new Date(epochMs)).map((part) => [part.type, Number(part.value)]),
  );
  return { year: parts.year, month: parts.month, day: parts.day, hour: parts.hour, minute: parts.minute, second: parts.second };
}

/** Offset of the shop's time zone from UTC at an instant, in milliseconds. */
function shopOffset(epochMs: number): number {
  const { year, month, day, hour, minute, second } = shopClock(epochMs);
  return Date.UTC(year, month - 1, day, hour, minute, second) - Math.floor(epochMs / 1000) * 1000;
}

const pad = (value: number) => String(value).padStart(2, "0");

/**
 * ISO instant → value of an <input type="datetime-local">, in the shop's time zone like every
 * date shown on the site (the browser's own time zone may differ).
 */
export function toDateTimeLocal(instant: string | null | undefined): string {
  if (!instant) {
    return "";
  }
  const epochMs = new Date(instant).getTime();
  if (Number.isNaN(epochMs)) {
    return "";
  }
  const { year, month, day, hour, minute } = shopClock(epochMs);
  return `${year}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}`;
}

/** Value of an <input type="datetime-local"> (shop's time zone) → ISO instant; empty → null. */
export function fromDateTimeLocal(value: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) {
    return null;
  }
  const [year, month, day, hour, minute, second = 0] = match.slice(1).map((part) => (part ? Number(part) : 0));
  const wallClock = Date.UTC(year, month - 1, day, hour, minute, second);
  // The offset at the guessed instant, corrected once for a change of offset in between
  const guess = wallClock - shopOffset(wallClock);
  const epochMs = wallClock - shopOffset(guess);
  return Number.isNaN(epochMs) ? null : new Date(epochMs).toISOString();
}
