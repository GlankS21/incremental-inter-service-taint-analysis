import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { SynonymManager } from "@/components/admin/SynonymManager";
import { Pagination, pageIndex } from "@/components/Pagination";
import { PageTitle } from "@/components/ui";
import { normalizeQuery } from "@/lib/search";
import { apiGet, keepPageInRange, query } from "@/lib/server/data";
import { param, type SearchParams } from "@/lib/search-params";
import type { PageResponse, SynonymGroup } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminSynonyms"))("metaTitle") };
}

export default async function AdminSynonymsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const keyword = param(params, "keyword")?.slice(0, 60);
  // Prefilled from "Add a synonym" on the statistics page
  const term = normalizeQuery(param(params, "term") ?? "").slice(0, 60) || undefined;
  const page = pageIndex(param(params, "page"));
  const [groups, t, tCommon] = await Promise.all([
    apiGet<PageResponse<SynonymGroup>>(`/api/search/admin/synonyms${query({ keyword, page, size: 20 })}`, { auth: true }),
    getTranslations("adminSynonyms"),
    getTranslations("common"),
  ]);
  keepPageInRange(page, groups, "/admin/search/synonyms", { keyword });

  return (
    <>
      <Link href="/admin/search" className="link text-sm">
        {t("back")}
      </Link>
      <div className="mt-3">
        <PageTitle>{t("title")}</PageTitle>
      </div>
      <p className="-mt-3 mb-6 max-w-2xl text-sm text-slate-500">{t("intro")}</p>

      {/* Remounted when the prefilled term changes */}
      <SynonymManager key={term ?? ""} groups={groups.content} initialTerm={term}>
        <div>
          <form method="get" className="flex flex-wrap gap-2">
            <input
              name="keyword"
              defaultValue={keyword}
              maxLength={60}
              placeholder={t("searchPlaceholder")}
              className="input max-w-xs"
              aria-label={t("searchPlaceholder")}
            />
            <button type="submit" className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
              {tCommon("search")}
            </button>
          </form>
          {groups.content.length === 0 && (
            <p className="card mt-4 p-4 text-sm text-slate-500">{keyword ? t("emptyFiltered", { keyword }) : t("empty")}</p>
          )}
        </div>
      </SynonymManager>
      <Pagination page={groups.page} totalPages={groups.totalPages} basePath="/admin/search/synonyms" params={{ keyword }} />
    </>
  );
}
