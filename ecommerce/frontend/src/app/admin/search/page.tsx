import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { Alert, ButtonLink, PageTitle } from "@/components/ui";
import { formatDate, formatDateTime, formatDecimal, formatPercent } from "@/lib/format";
import { searchHref } from "@/lib/search";
import { apiGet, query } from "@/lib/server/data";
import { param, type SearchParams } from "@/lib/search-params";
import type { SearchStats } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminSearch"))("metaTitle") };
}

const PERIODS = [7, 30, 90, 365] as const;
const DEFAULT_PERIOD = 30;

/** What customers search for, and the searches that found nothing (candidates for synonyms). */
export default async function AdminSearchPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const requested = Number(param(await searchParams, "days"));
  const days = PERIODS.find((period) => period === requested) ?? DEFAULT_PERIOD;
  const [stats, t] = await Promise.all([
    apiGet<SearchStats>(`/api/search/admin/stats${query({ days, limit: 20 })}`, { auth: true }).catch(() => null),
    getTranslations("adminSearch"),
  ]);

  const periods = (
    <nav aria-label={t("period")} className="flex gap-1 rounded-lg bg-slate-100 p-1">
      {PERIODS.map((period) => (
        <Link
          key={period}
          href={period === DEFAULT_PERIOD ? "/admin/search" : `/admin/search?days=${period}`}
          aria-current={period === days ? "page" : undefined}
          className={`rounded-md px-3 py-1 text-sm whitespace-nowrap ${
            period === days ? "bg-white font-medium text-slate-900 shadow-sm" : "text-slate-600 hover:text-slate-900"
          }`}
        >
          {t("days", { count: period })}
        </Link>
      ))}
    </nav>
  );

  return (
    <>
      <PageTitle
        actions={
          <ButtonLink href="/admin/search/synonyms" variant="secondary">
            {t("synonyms")}
          </ButtonLink>
        }
      >
        {t("title")}
      </PageTitle>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        {periods}
        {stats && (
          <p className="text-sm text-slate-500">{t("range", { from: formatDate(stats.from), to: formatDate(stats.to) })}</p>
        )}
      </div>

      {!stats ? (
        <Alert tone="warning">{t("unavailable")}</Alert>
      ) : (
        <div className="flex flex-col gap-8">
          <div className="grid gap-4 sm:grid-cols-3">
            <div className="card p-5">
              <p className="text-sm text-slate-500">{t("totalSearches")}</p>
              <p className="mt-2 text-3xl font-semibold text-slate-900">{stats.totalSearches}</p>
            </div>
            <div className="card p-5">
              <p className="text-sm text-slate-500">{t("uniqueQueries")}</p>
              <p className="mt-2 text-3xl font-semibold text-slate-900">{stats.uniqueQueries}</p>
            </div>
            <div className="card p-5">
              <p className="text-sm text-slate-500">{t("zeroResultRate")}</p>
              <p className="mt-2 text-3xl font-semibold text-rose-700">{formatPercent(stats.zeroResultRate)}</p>
              <p className="mt-1 text-xs text-slate-500">{t("zeroResultSearches", { count: stats.zeroResultSearches })}</p>
            </div>
          </div>

          <section className="card overflow-x-auto" aria-labelledby="top-queries">
            <h2 id="top-queries" className="p-4 font-semibold text-slate-900">
              {t("topQueries")}
            </h2>
            {stats.topQueries.length === 0 ? (
              <p className="px-4 pb-4 text-sm text-slate-500">{t("noTopQueries")}</p>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th>{t("query")}</th>
                    <th className="text-right">{t("searches")}</th>
                    <th className="text-right">{t("averageResults")}</th>
                    <th>{t("lastSearched")}</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.topQueries.map((row) => (
                    <tr key={row.query}>
                      <td>
                        <Link
                          href={searchHref(row.query)}
                          aria-label={t("openResults", { query: row.query })}
                          className="font-medium text-brand-700 hover:underline"
                        >
                          {row.query}
                        </Link>
                      </td>
                      <td className="text-right">{row.searches}</td>
                      <td className="text-right">{formatDecimal(row.averageResults)}</td>
                      <td className="whitespace-nowrap text-slate-600">{formatDateTime(row.lastSearchedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section className="card overflow-x-auto" aria-labelledby="zero-queries">
            <div className="p-4">
              <h2 id="zero-queries" className="font-semibold text-slate-900">
                {t("zeroResultQueries")}
              </h2>
              <p className="mt-1 text-sm text-slate-500">{t("zeroResultHint")}</p>
            </div>
            {stats.zeroResultQueries.length === 0 ? (
              <p className="px-4 pb-4 text-sm text-slate-500">{t("noZeroResultQueries")}</p>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th>{t("query")}</th>
                    <th className="text-right">{t("searches")}</th>
                    <th>{t("lastSearched")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {stats.zeroResultQueries.map((row) => (
                    <tr key={row.query}>
                      <td className="font-medium text-slate-900">{row.query}</td>
                      <td className="text-right">{row.searches}</td>
                      <td className="whitespace-nowrap text-slate-600">{formatDateTime(row.lastSearchedAt)}</td>
                      <td className="text-right">
                        <ButtonLink href={`/admin/search/synonyms${query({ term: row.query })}`} variant="secondary" size="sm">
                          {t("addSynonym")}
                        </ButtonLink>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </div>
      )}
    </>
  );
}
