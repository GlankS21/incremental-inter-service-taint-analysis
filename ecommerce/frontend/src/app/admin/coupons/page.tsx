import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { Pagination, pageIndex } from "@/components/Pagination";
import { ButtonLink, PageTitle, StatusBadge } from "@/components/ui";
import { COUPON_STATE_TONE, couponState } from "@/lib/coupons";
import { formatDate, formatMoney, labelled } from "@/lib/format";
import { apiGet, keepPageInRange, query } from "@/lib/server/data";
import { oneOf, param, type SearchParams } from "@/lib/search-params";
import type { Coupon, PageResponse } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminCoupons"))("metaTitle") };
}

const ACTIVE_FILTERS = ["true", "false"] as const;

export default async function AdminCouponsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const keyword = param(params, "keyword")?.slice(0, 100);
  const active = oneOf(param(params, "active"), ACTIVE_FILTERS);
  const page = pageIndex(param(params, "page"));
  const [coupons, t, tCommon] = await Promise.all([
    apiGet<PageResponse<Coupon>>(`/api/orders/admin/coupons${query({ keyword, active, page, size: 20 })}`, { auth: true }),
    getTranslations("adminCoupons"),
    getTranslations("common"),
  ]);
  keepPageInRange(page, coupons, "/admin/coupons", { keyword, active });
  const states = labelled(COUPON_STATE_TONE, (state) => t(state));

  return (
    <>
      <PageTitle actions={<ButtonLink href="/admin/coupons/new">{t("new")}</ButtonLink>}>{t("title")}</PageTitle>
      <form method="get" className="mb-4 flex flex-wrap gap-2">
        <input
          name="keyword"
          defaultValue={keyword}
          maxLength={100}
          placeholder={t("searchPlaceholder")}
          className="input max-w-xs"
          aria-label={t("searchPlaceholder")}
        />
        <select name="active" defaultValue={active ?? ""} className="input w-auto" aria-label={t("filterActive")}>
          <option value="">{tCommon("all")}</option>
          <option value="true">{t("active")}</option>
          <option value="false">{t("inactive")}</option>
        </select>
        <button type="submit" className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
          {tCommon("search")}
        </button>
      </form>
      <div className="card overflow-x-auto">
        <table className="table">
          <thead>
            <tr>
              <th>{t("code")}</th>
              <th>{t("discount")}</th>
              <th>{t("conditions")}</th>
              <th>{t("validity")}</th>
              <th className="text-right">{t("usage")}</th>
              <th>{t("filterActive")}</th>
            </tr>
          </thead>
          <tbody>
            {coupons.content.map((coupon) => {
              const conditions = [
                coupon.minOrderAmount !== null ? t("minOrder", { amount: formatMoney(coupon.minOrderAmount) }) : null,
                coupon.maxDiscountAmount !== null && coupon.discountType === "PERCENTAGE"
                  ? t("maxDiscount", { amount: formatMoney(coupon.maxDiscountAmount) })
                  : null,
              ].filter(Boolean);
              const validity = [
                coupon.startsAt ? t("from", { date: formatDate(coupon.startsAt) }) : null,
                coupon.expiresAt ? t("until", { date: formatDate(coupon.expiresAt) }) : null,
              ].filter(Boolean);
              return (
                <tr key={coupon.id}>
                  <td>
                    <Link href={`/admin/coupons/${coupon.id}`} className="font-mono font-medium text-brand-700 hover:underline">
                      {coupon.code}
                    </Link>
                    {coupon.description && <p className="max-w-64 truncate text-xs text-slate-500">{coupon.description}</p>}
                  </td>
                  <td className="whitespace-nowrap">
                    {coupon.discountType === "PERCENTAGE"
                      ? t("percentValue", { value: coupon.discountValue })
                      : formatMoney(coupon.discountValue)}
                  </td>
                  <td className="text-slate-600">{conditions.length > 0 ? conditions.join(", ") : t("noConditions")}</td>
                  <td className="whitespace-nowrap text-slate-600">{validity.length > 0 ? validity.join(" ") : t("always")}</td>
                  <td className="text-right whitespace-nowrap">
                    {coupon.usageLimit !== null
                      ? t("usageValue", { used: coupon.usedCount, limit: coupon.usageLimit })
                      : t("usageUnlimited", { used: coupon.usedCount })}
                  </td>
                  <td>
                    <StatusBadge value={couponState(coupon)} labels={states} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {coupons.content.length === 0 && <p className="p-4 text-sm text-slate-500">{t("empty")}</p>}
      </div>
      <Pagination page={coupons.page} totalPages={coupons.totalPages} basePath="/admin/coupons" params={{ keyword, active }} />
    </>
  );
}
