import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { DeleteReviewButton } from "@/components/admin/DeleteReviewButton";
import { Pagination, pageIndex } from "@/components/Pagination";
import { RatingStars } from "@/components/product/RatingStars";
import { PageTitle } from "@/components/ui";
import { formatDateTime } from "@/lib/format";
import { apiGet, keepPageInRange, query } from "@/lib/server/data";
import { oneOf, param, positiveInt, type SearchParams } from "@/lib/search-params";
import type { AdminReview, PageResponse } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminReviews"))("metaTitle") };
}

const RATINGS = ["1", "2", "3", "4", "5"] as const;

export default async function AdminReviewsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const rating = oneOf(param(params, "rating"), RATINGS);
  const productId = positiveInt(param(params, "productId"));
  const page = pageIndex(param(params, "page"));
  const [reviews, t, tCommon, tReviews] = await Promise.all([
    apiGet<PageResponse<AdminReview>>(`/api/reviews/admin${query({ rating, productId, page, size: 20 })}`, { auth: true }),
    getTranslations("adminReviews"),
    getTranslations("common"),
    getTranslations("reviews"),
  ]);
  keepPageInRange(page, reviews, "/admin/reviews", { rating, productId });

  return (
    <>
      <PageTitle>{t("title")}</PageTitle>
      <form method="get" className="mb-4 flex flex-wrap gap-2">
        {productId && <input type="hidden" name="productId" value={productId} />}
        <select name="rating" defaultValue={rating ?? ""} className="input w-auto" aria-label={t("filterRating")}>
          <option value="">{tCommon("all")}</option>
          {RATINGS.map((value) => (
            <option key={value} value={value}>
              {tReviews("star", { count: Number(value) })}
            </option>
          ))}
        </select>
        <button type="submit" className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
          {tCommon("search")}
        </button>
      </form>
      <div className="card overflow-x-auto">
        <table className="table">
          <thead>
            <tr>
              <th>{t("product")}</th>
              <th>{t("rating")}</th>
              <th>{t("comment")}</th>
              <th>{t("author")}</th>
              <th>{t("date")}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {reviews.content.map((review) => (
              <tr key={review.id}>
                <td>
                  <Link href={`/products/${review.productSlug}#reviews`} className="line-clamp-2 max-w-56 font-medium hover:text-brand-600">
                    {review.productName}
                  </Link>
                </td>
                <td>
                  <RatingStars value={review.rating} size="sm" />
                </td>
                <td className="max-w-96">
                  {review.comment ? (
                    <p className="line-clamp-3 break-words text-slate-700" title={review.comment}>
                      {review.comment}
                    </p>
                  ) : (
                    <span className="text-slate-400">{t("noComment")}</span>
                  )}
                </td>
                <td>
                  <Link href={`/admin/accounts/${review.userId}`} className="hover:text-brand-600">
                    {review.authorName}
                  </Link>
                </td>
                <td className="whitespace-nowrap text-slate-500">{formatDateTime(review.createdAt)}</td>
                <td className="text-right">
                  <DeleteReviewButton review={review} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {reviews.content.length === 0 && <p className="p-4 text-sm text-slate-500">{t("empty")}</p>}
      </div>
      <Pagination page={reviews.page} totalPages={reviews.totalPages} basePath="/admin/reviews" params={{ rating, productId }} />
    </>
  );
}
