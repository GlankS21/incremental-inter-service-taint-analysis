import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { AccountReviews } from "@/components/review/AccountReviews";
import { PageTitle } from "@/components/ui";
import { ACCOUNT_REVIEW_TABS } from "@/lib/reviews";
import { oneOf, param, positiveInt, type SearchParams } from "@/lib/search-params";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("myReviews"))("metaTitle") };
}

/**
 * "Мои отзывы": products waiting for a review and the reviews already written. ?product=<id> opens the
 * review dialog of that product (links of the reminder e-mail); signed-out visitors go through the login
 * page and come back here (proxy.ts).
 */
export default async function AccountReviewsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const t = await getTranslations("myReviews");
  return (
    <>
      <PageTitle>{t("title")}</PageTitle>
      <p className="-mt-4 mb-6 text-sm text-slate-500">{t("intro")}</p>
      <AccountReviews
        initialTab={oneOf(param(params, "tab"), ACCOUNT_REVIEW_TABS) ?? "pending"}
        productId={positiveInt(param(params, "product")) ?? null}
      />
    </>
  );
}
