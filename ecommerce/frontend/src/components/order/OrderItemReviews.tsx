"use client";

import { useTranslations } from "next-intl";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { RatingInput } from "@/components/product/RatingInput";
import { RatingStars } from "@/components/product/RatingStars";
import { ReviewModal } from "@/components/review/ReviewModal";
import { Button } from "@/components/ui";
import { api } from "@/lib/client/api";
import { MAX_REVIEW_STATUS_IDS } from "@/lib/reviews";
import type { Review, ReviewStatus } from "@/lib/types";

interface OrderReviewsState {
  /** null while loading; failed when product-service did not answer. */
  statuses: Map<number, ReviewStatus> | null;
  failed: boolean;
  update: (productId: number, review: Review | null) => void;
}

const OrderReviewsContext = createContext<OrderReviewsState>({ statuses: null, failed: false, update: () => {} });

/**
 * Review state of every product of a delivered order, loaded with one call to /api/reviews/status
 * (several calls only for an order of more than {@link MAX_REVIEW_STATUS_IDS} products).
 */
export function OrderReviews({ productIds, children }: { productIds: number[]; children: ReactNode }) {
  const t = useTranslations("reviews");
  const [statuses, setStatuses] = useState<Map<number, ReviewStatus> | null>(null);
  const [failed, setFailed] = useState(false);
  const key = productIds.join(",");

  useEffect(() => {
    const ids = key ? key.split(",") : [];
    const chunks: string[][] = [];
    for (let index = 0; index < ids.length; index += MAX_REVIEW_STATUS_IDS) {
      chunks.push(ids.slice(index, index + MAX_REVIEW_STATUS_IDS));
    }
    let cancelled = false;
    Promise.all(chunks.map((chunk) => api.get<ReviewStatus[]>(`/reviews/status?productIds=${chunk.join(",")}`)))
      .then((results) => {
        if (!cancelled) {
          setStatuses(new Map(results.flat().map((status) => [status.productId, status])));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [key]);

  const update = useCallback((productId: number, review: Review | null) => {
    setStatuses((current) => {
      const previous = current?.get(productId);
      if (!current || !previous) {
        return current;
      }
      const next = new Map(current);
      next.set(productId, { ...previous, reviewed: review !== null, rating: review?.rating ?? null });
      return next;
    });
  }, []);

  const value = useMemo(() => ({ statuses, failed, update }), [statuses, failed, update]);
  return (
    <OrderReviewsContext.Provider value={value}>
      {failed && (
        <p role="status" className="mb-4 text-sm text-amber-700">
          {t("statusLoadError")}
        </p>
      )}
      {children}
    </OrderReviewsContext.Provider>
  );
}

/**
 * Under a product of a delivered order: "Оставить отзыв" with a star picker (choosing stars opens the
 * dialog with them), or the customer's rating with "Изменить отзыв". Nothing for products that cannot
 * be reviewed (purchase not recorded yet, or withdrawn from the catalogue).
 */
export function OrderItemReview({ productId, productName }: { productId: number; productName: string }) {
  const t = useTranslations("reviews");
  const { statuses, update } = useContext(OrderReviewsContext);
  const [dialog, setDialog] = useState<{ rating?: number } | null>(null);
  const status = statuses?.get(productId);

  if (!status || !status.canReview) {
    return null;
  }
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2">
      {status.reviewed && status.rating !== null ? (
        <>
          <span className="flex items-center gap-1.5 text-sm text-slate-600">
            {t("yourRating")}: <RatingStars value={status.rating} size="sm" />
          </span>
          <Button variant="secondary" size="sm" onClick={() => setDialog({})}>
            {t("editReview")}
          </Button>
        </>
      ) : (
        <>
          <RatingInput size="md" value={0} label={t("rateProduct", { name: productName })} onChange={(rating) => setDialog({ rating })} />
          <Button size="sm" onClick={() => setDialog({})}>
            {t("writeReview")}
          </Button>
        </>
      )}
      {dialog && (
        <ReviewModal
          productId={productId}
          productName={productName}
          initialRating={dialog.rating}
          onClose={() => setDialog(null)}
          onSaved={(review) => update(productId, review)}
        />
      )}
    </div>
  );
}
