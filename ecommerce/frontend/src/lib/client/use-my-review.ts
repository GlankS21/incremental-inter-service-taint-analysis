"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/client/api";
import type { MyReview, Review, UserProfile } from "@/lib/types";

export type MyReviewState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "ready"; mine: MyReview; defaultName: string };

/**
 * Whether the signed-in customer may review the product, their review, and the display name suggested
 * for a new review. Loads nothing while {@code enabled} is false (signed out, or a closed dialog).
 */
export function useMyReview(productId: number, enabled: boolean) {
  const [state, setState] = useState<MyReviewState>({ kind: "idle" });

  useEffect(() => {
    if (!enabled) {
      return;
    }
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState({ kind: "loading" });
    Promise.all([
      api.get<MyReview>(`/reviews/products/${productId}/mine`),
      // Suggested display name; the review form works without it
      api.get<UserProfile>("/users/me").catch(() => null),
    ])
      .then(([mine, profile]) => {
        if (!cancelled) {
          setState({ kind: "ready", mine, defaultName: mine.review?.authorName ?? profile?.firstName ?? "" });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setState({ kind: "failed" });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [productId, enabled]);

  /** Keeps the state in line with a review just saved (or deleted: null). */
  const setReview = useCallback((review: Review | null) => {
    setState((current) => (current.kind === "ready" ? { ...current, mine: { ...current.mine, review } } : current));
  }, []);

  return { state, setReview };
}
