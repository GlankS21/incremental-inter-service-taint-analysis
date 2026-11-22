/**
 * Reviews per page on the product page. It lives outside the client component so that the server page
 * can read it too: a server component only gets a client reference for exports of a "use client" module.
 */
export const REVIEWS_PAGE_SIZE = 5;

/** Items per page on /account/reviews. */
export const ACCOUNT_REVIEWS_PAGE_SIZE = 10;

export const MAX_RATING = 5;

/** product-service answers /api/reviews/status and /api/reviews/pending/count for at most this many ids. */
export const MAX_REVIEW_STATUS_IDS = 50;

/** Tabs of /account/reviews, also accepted as ?tab=. */
export const ACCOUNT_REVIEW_TABS = ["pending", "mine"] as const;
export type AccountReviewTab = (typeof ACCOUNT_REVIEW_TABS)[number];

/** Text of a count badge: nothing for zero or unknown, «99+» above 99. */
export function badgeText(count: number | null | undefined): string | null {
  if (!count || count < 1) {
    return null;
  }
  return count > 99 ? "99+" : String(count);
}

/**
 * Star rating after a key press in the star picker (arrows, Home/End, digits 1–5); null when the key does
 * not change the rating. From no rating, any arrow chooses one star.
 */
export function ratingAfterKey(current: number, key: string): number | null {
  switch (key) {
    case "ArrowRight":
    case "ArrowUp":
      return Math.min(MAX_RATING, Math.max(1, current + 1));
    case "ArrowLeft":
    case "ArrowDown":
      return Math.max(1, current - 1);
    case "Home":
      return 1;
    case "End":
      return MAX_RATING;
    default:
      return /^[1-5]$/.test(key) ? Number(key) : null;
  }
}
