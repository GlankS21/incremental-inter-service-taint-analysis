"use client";

import { useTranslations } from "next-intl";
import { usePendingReviews } from "@/components/providers";
import { badgeText } from "@/lib/reviews";

/** Number of products waiting for the customer's review; nothing when there is none. */
export function PendingReviewsBadge() {
  const { count } = usePendingReviews();
  return <PendingCountBadge count={count} />;
}

/** Markup of {@link PendingReviewsBadge}. */
export function PendingCountBadge({ count }: { count: number | null }) {
  const t = useTranslations("nav");
  const text = badgeText(count);
  if (text === null || count === null) {
    return null;
  }
  return (
    <span className="ml-auto inline-flex min-w-5 items-center justify-center rounded-full bg-accent-500 px-1.5 text-[11px] leading-5 font-semibold text-white">
      <span aria-hidden="true">{text}</span>
      <span className="sr-only">{t("pendingReviews", { count })}</span>
    </span>
  );
}
