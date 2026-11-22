"use client";

import { useTranslations } from "next-intl";
import { INTL_LOCALE } from "@/i18n/config";
import { useCallback, useState } from "react";
import { ReviewEditor } from "@/components/product/ReviewEditor";
import { RatingStars } from "@/components/product/RatingStars";
import { VerifiedPurchase } from "@/components/product/VerifiedPurchase";
import { Alert, Button, EmptyState } from "@/components/ui";
import { api } from "@/lib/client/api";
import { formatDate } from "@/lib/format";
import { REVIEWS_PAGE_SIZE } from "@/lib/reviews";
import type { PageResponse, Review } from "@/lib/types";

interface Props {
  productId: number;
  productSlug: string;
  ratingAverage: number;
  ratingCount: number;
  /** First page rendered on the server; null when product-service did not answer. */
  initial: PageResponse<Review> | null;
}

/** Rating summary, the customer's own review form and the published reviews ("show more" pages). */
export function ProductReviews({ productId, productSlug, ratingAverage, ratingCount, initial }: Props) {
  const t = useTranslations("reviews");
  const tCommon = useTranslations("common");
  const [reviews, setReviews] = useState<Review[]>(initial?.content ?? []);
  const [page, setPage] = useState(initial?.page ?? 0);
  const [last, setLast] = useState(initial?.last ?? true);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(initial === null);

  const load = useCallback(
    async (target: number) => {
      setLoading(true);
      try {
        const result = await api.get<PageResponse<Review>>(
          `/products/${productId}/reviews?page=${target}&size=${REVIEWS_PAGE_SIZE}`,
        );
        setReviews((current) => (target === 0 ? result.content : [...current, ...result.content]));
        setPage(result.page);
        setLast(result.last);
        setFailed(false);
      } catch {
        setFailed(true);
      } finally {
        setLoading(false);
      }
    },
    [productId],
  );

  return (
    <section id="reviews" className="card mt-10 scroll-mt-32 p-6" aria-labelledby="reviews-title">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <h2 id="reviews-title" className="text-lg font-semibold text-slate-900">
          {t("title")}
        </h2>
        {ratingCount > 0 && (
          <div className="flex items-center gap-2 text-sm text-slate-600">
            <RatingStars value={ratingAverage} size="lg" />
            <span className="font-semibold text-slate-900">{ratingAverage.toLocaleString(INTL_LOCALE, { minimumFractionDigits: 1 })}</span>
            <span>· {t("count", { count: ratingCount })}</span>
          </div>
        )}
      </div>

      <div className="mt-5">
        <ReviewEditor productId={productId} productSlug={productSlug} onChanged={() => void load(0)} />
      </div>

      <div className="mt-6 border-t border-slate-100 pt-2">
        {failed && reviews.length === 0 ? (
          <div className="pt-4">
            <Alert tone="warning">{t("loadError")}</Alert>
          </div>
        ) : reviews.length === 0 ? (
          <div className="pt-4">
            <EmptyState title={t("noReviews")} />
          </div>
        ) : (
          <ul className="flex flex-col divide-y divide-slate-100">
            {reviews.map((review) => (
              <li key={review.id} className="py-4">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <RatingStars value={review.rating} size="sm" />
                  <span className="font-medium text-slate-900">{review.authorName}</span>
                  {review.verifiedPurchase && <VerifiedPurchase />}
                  <time className="text-xs text-slate-500" dateTime={review.createdAt}>
                    {formatDate(review.createdAt)}
                  </time>
                  {review.updatedAt !== review.createdAt && (
                    <span className="text-xs text-slate-400">{t("edited", { date: formatDate(review.updatedAt) })}</span>
                  )}
                </div>
                {review.comment && <p className="mt-2 text-sm leading-relaxed break-words whitespace-pre-line text-slate-700">{review.comment}</p>}
              </li>
            ))}
          </ul>
        )}
        {!last && (
          <div className="pt-2">
            <Button variant="secondary" size="sm" loading={loading} onClick={() => void load(page + 1)}>
              {tCommon("showMore")}
            </Button>
          </div>
        )}
      </div>
    </section>
  );
}
