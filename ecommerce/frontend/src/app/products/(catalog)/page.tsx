import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { Pagination, pageIndex } from "@/components/Pagination";
import { ProductCard } from "@/components/ProductCard";
import { Alert, EmptyState } from "@/components/ui";
import { formatMoney } from "@/lib/format";
import {
  catalogSort,
  catalogSortOptions,
  isPriceRangeActive,
  priceRangeFilter,
  searchHref,
  type CatalogSort,
} from "@/lib/search";
import { apiGet, keepPageInRange, query } from "@/lib/server/data";
import { nonNegativeNumber, param, positiveInt, type SearchParams } from "@/lib/search-params";
import type { Category, PageResponse, PopularSearches, ProductSearchResponse, ProductSummary } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("catalog"))("metaTitle") };
}

const PAGE_SIZE = 12;

/** Message key of each sort option in the "catalog" namespace. */
const SORT_LABELS = {
  relevance: "sortRelevance",
  "salesCount,desc": "sortPopular",
  "createdAt,desc": "sortNewest",
  "price,asc": "sortPriceAsc",
  "price,desc": "sortPriceDesc",
  "name,asc": "sortName",
  "ratingAverage,desc": "sortRating",
} as const satisfies Record<CatalogSort, string>;

/** Everything the catalogue keeps in the URL, except the page (any change of a filter goes back to page 1). */
type Filters = {
  keyword?: string;
  categoryId?: number;
  brand?: string;
  minPrice?: number;
  maxPrice?: number;
  sort?: CatalogSort;
};

export default async function ProductsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const keyword = param(params, "keyword")?.slice(0, 100);
  const categoryId = positiveInt(param(params, "categoryId"));
  const brand = param(params, "brand")?.slice(0, 100);
  const minPrice = nonNegativeNumber(param(params, "minPrice"));
  const maxPrice = nonNegativeNumber(param(params, "maxPrice"));
  const sort = catalogSort(param(params, "sort"), Boolean(keyword));
  const page = pageIndex(param(params, "page"));
  const [categories, products, t] = await Promise.all([
    apiGet<Category[]>("/api/categories").catch(() => [] as Category[]),
    apiGet<ProductSearchResponse>(
      `/api/products${query({ keyword, categoryId, brand, minPrice, maxPrice, sort, page, size: PAGE_SIZE })}`,
    ).catch(() => null),
    getTranslations("catalog"),
  ]);
  // Nothing found for a search: suggest popular categories and best sellers instead
  const searchEmpty = Boolean(keyword) && products?.totalElements === 0;
  const [popular, bestsellers] = searchEmpty
    ? await Promise.all([
        apiGet<PopularSearches>("/api/products/search/popular").catch(() => null),
        apiGet<PageResponse<ProductSummary>>("/api/products?sort=salesCount,desc&size=4").catch(() => null),
      ])
    : [null, null];

  const current: Filters = { keyword, categoryId, brand, minPrice, maxPrice, sort };
  if (products) {
    keepPageInRange(page, products, "/products", current);
  }
  const href = (changes: Filters) => {
    const next = { ...current, ...changes };
    // Relevance means nothing without a keyword
    return `/products${query({ ...next, sort: !next.keyword && next.sort === "relevance" ? undefined : next.sort })}`;
  };
  const facets = products?.facets;
  const hasFilter = brand !== undefined || minPrice !== undefined || maxPrice !== undefined;
  const categoryName =
    categories.flatMap((root) => [root, ...root.children]).find((item) => item.id === categoryId)?.name ??
    facets?.categories.find((item) => item.id === categoryId)?.name;
  // With a search or a filter the counts of the matching products are more useful than the whole tree
  const categoryFacets = (keyword || hasFilter) && facets && facets.categories.length > 0 ? facets.categories : null;
  const brandFacets = facets?.brands ?? [];
  const brandList = brand && !brandFacets.some((item) => item.name === brand) ? [{ name: brand, count: null }, ...brandFacets] : brandFacets;

  const money = (amount: number) => formatMoney(amount);
  const rangeLabel = (from: number | null | undefined, to: number | null | undefined) =>
    from !== null && from !== undefined && to !== null && to !== undefined
      ? t("priceBetween", { from: money(from), to: money(to) })
      : from !== null && from !== undefined
        ? t("priceOver", { from: money(from) })
        : t("priceUnder", { to: money(to ?? 0) });

  const chips = [
    keyword ? { label: t("chipKeyword", { keyword }), href: href({ keyword: undefined }) } : null,
    categoryId
      ? { label: t("chipCategory", { name: categoryName ?? String(categoryId) }), href: href({ categoryId: undefined }) }
      : null,
    brand ? { label: t("chipBrand", { name: brand }), href: href({ brand: undefined }) } : null,
    minPrice !== undefined || maxPrice !== undefined
      ? { label: t("chipPrice", { range: rangeLabel(minPrice, maxPrice) }), href: href({ minPrice: undefined, maxPrice: undefined }) }
      : null,
  ].filter((chip) => chip !== null);

  const corrected = products?.correctedKeyword ?? null;
  const shownKeyword = products?.correctionApplied && corrected ? corrected : keyword;
  const title = keyword
    ? !products
      ? t("searchTitle", { keyword })
      : products.totalElements === 0
        ? t("searchEmpty", { keyword })
        : t("searchFound", { count: products.totalElements, keyword: shownKeyword ?? keyword })
    : (categoryName ?? t("allProducts"));

  const facetLinkClass = (active: boolean) =>
    `flex items-center gap-2 ${active ? "font-medium text-brand-600" : "text-slate-600 hover:text-brand-600"}`;
  const checkbox = (active: boolean) => (
    <span
      aria-hidden="true"
      className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border text-[10px] leading-none ${
        active ? "border-brand-600 bg-brand-600 text-white" : "border-slate-300 bg-white"
      }`}
    >
      {active ? "✓" : ""}
    </span>
  );

  return (
    <div className="container-page py-8">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
        {products && !keyword && <p className="mt-1 text-sm text-slate-500">{t("count", { count: products.totalElements })}</p>}
        {corrected && (
          <div className="mt-2 flex flex-col gap-1 text-sm text-slate-600">
            <p>
              {t.rich("didYouMean", {
                keyword: corrected,
                link: (chunks) => (
                  <Link href={searchHref(corrected)} className="link font-medium">
                    {chunks}
                  </Link>
                ),
              })}
            </p>
            {products?.correctionApplied && keyword && <p>{t("correctionApplied", { original: keyword, corrected })}</p>}
          </div>
        )}
        {chips.length > 0 && (
          <div className="mt-4 flex flex-wrap items-center gap-2" aria-label={t("activeFilters")} role="group">
            {chips.map((chip) => (
              <Link
                key={chip.label}
                href={chip.href}
                aria-label={t("removeFilter", { label: chip.label })}
                className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-brand-50 px-3 py-1 text-sm text-brand-700 hover:bg-brand-100"
              >
                <span className="truncate">{chip.label}</span>
                <span aria-hidden="true">×</span>
              </Link>
            ))}
            {(categoryId || hasFilter) && (
              <Link
                href={href({ categoryId: undefined, brand: undefined, minPrice: undefined, maxPrice: undefined })}
                className="link text-sm"
              >
                {t("resetFilters")}
              </Link>
            )}
          </div>
        )}
      </div>

      <div className="grid gap-8 lg:grid-cols-[240px_1fr]">
        <aside className="flex flex-col gap-6">
          <nav aria-label={t("categoryFilter")} className="card p-4">
            <h2 className="mb-2 text-sm font-semibold text-slate-900">{t("categories")}</h2>
            <ul className="flex flex-col gap-1 text-sm">
              <li>
                <Link
                  href={href({ categoryId: undefined })}
                  className={!categoryId ? "font-medium text-brand-600" : "text-slate-600 hover:text-brand-600"}
                >
                  {t("allCategories")}
                </Link>
              </li>
              {categoryFacets
                ? categoryFacets.map((item) => (
                    <li key={item.id}>
                      <Link
                        href={href({ categoryId: item.id })}
                        aria-current={item.id === categoryId ? "true" : undefined}
                        className={`flex justify-between gap-2 ${
                          item.id === categoryId ? "font-medium text-brand-600" : "text-slate-700 hover:text-brand-600"
                        }`}
                      >
                        <span>{item.name}</span>
                        <span className="text-xs text-slate-400">{item.count}</span>
                      </Link>
                    </li>
                  ))
                : categories.map((root) => (
                    <li key={root.id}>
                      <Link
                        href={href({ categoryId: root.id })}
                        className={root.id === categoryId ? "font-medium text-brand-600" : "text-slate-700 hover:text-brand-600"}
                      >
                        {root.name}
                      </Link>
                      <ul className="mt-1 mb-1 ml-3 flex flex-col gap-1 border-l border-slate-200 pl-3">
                        {root.children.map((child) => (
                          <li key={child.id}>
                            <Link
                              href={href({ categoryId: child.id })}
                              className={child.id === categoryId ? "font-medium text-brand-600" : "text-slate-500 hover:text-brand-600"}
                            >
                              {child.name}
                            </Link>
                          </li>
                        ))}
                      </ul>
                    </li>
                  ))}
            </ul>
          </nav>

          {brandList.length > 0 && (
            <nav aria-label={t("brandFilter")} className="card p-4">
              <h2 className="mb-2 text-sm font-semibold text-slate-900">{t("brands")}</h2>
              <ul className="flex flex-col gap-1.5 text-sm">
                {brandList.map((item) => {
                  const active = item.name === brand;
                  return (
                    <li key={item.name}>
                      <Link
                        href={href({ brand: active ? undefined : item.name })}
                        aria-current={active ? "true" : undefined}
                        className={facetLinkClass(active)}
                      >
                        {checkbox(active)}
                        <span className="min-w-0 flex-1 truncate">{item.name}</span>
                        {item.count !== null && <span className="text-xs text-slate-400">{item.count}</span>}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </nav>
          )}

          {facets && facets.priceRanges.length > 0 && (
            <nav aria-label={t("priceFilter")} className="card p-4">
              <h2 className="mb-2 text-sm font-semibold text-slate-900">{t("price")}</h2>
              <ul className="flex flex-col gap-1.5 text-sm">
                {facets.priceRanges.map((range) => {
                  const active = isPriceRangeActive(range, minPrice, maxPrice);
                  return (
                    <li key={`${range.from}-${range.to}`}>
                      <Link
                        href={href(active ? { minPrice: undefined, maxPrice: undefined } : priceRangeFilter(range))}
                        aria-current={active ? "true" : undefined}
                        className={facetLinkClass(active)}
                      >
                        {checkbox(active)}
                        <span className="min-w-0 flex-1">{rangeLabel(range.from, range.to)}</span>
                        <span className="text-xs text-slate-400">{range.count}</span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </nav>
          )}

          <form method="get" action="/products" className="card flex flex-col gap-3 p-4">
            <h2 className="text-sm font-semibold text-slate-900">{t("filters")}</h2>
            {keyword && <input type="hidden" name="keyword" value={keyword} />}
            {categoryId && <input type="hidden" name="categoryId" value={categoryId} />}
            {brand && <input type="hidden" name="brand" value={brand} />}
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label htmlFor="minPrice" className="label">
                  {t("priceFrom")}
                </label>
                <input id="minPrice" name="minPrice" type="number" min={0} step={1} defaultValue={minPrice} className="input" />
              </div>
              <div>
                <label htmlFor="maxPrice" className="label">
                  {t("priceTo")}
                </label>
                <input id="maxPrice" name="maxPrice" type="number" min={0} step={1} defaultValue={maxPrice} className="input" />
              </div>
            </div>
            <div>
              <label htmlFor="sort" className="label">
                {t("sort")}
              </label>
              <select id="sort" name="sort" defaultValue={sort} className="input">
                {catalogSortOptions(Boolean(keyword)).map((option) => (
                  <option key={option} value={option}>
                    {t(SORT_LABELS[option])}
                  </option>
                ))}
              </select>
            </div>
            <button type="submit" className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
              {t("apply")}
            </button>
          </form>
        </aside>

        <section aria-label={t("results")}>
          {!products ? (
            <Alert tone="warning">{t("unavailable")}</Alert>
          ) : searchEmpty ? (
            <div className="flex flex-col gap-8">
              <div className="card p-6">
                <h2 className="mb-3 font-semibold text-slate-900">{t("tipsTitle")}</h2>
                <ul className="flex list-disc flex-col gap-1.5 pl-5 text-sm text-slate-600">
                  <li>{t("tipSpelling")}</li>
                  <li>{t("tipFewer")}</li>
                  <li>{t("tipSynonyms")}</li>
                  {(categoryId || hasFilter) && <li>{t("tipFilters")}</li>}
                </ul>
              </div>
              {popular && popular.categories.length > 0 && (
                <section aria-labelledby="popular-categories">
                  <h2 id="popular-categories" className="mb-3 text-lg font-semibold text-slate-900">
                    {t("popularCategories")}
                  </h2>
                  <ul className="flex flex-wrap gap-2">
                    {popular.categories.map((item) => (
                      <li key={item.id}>
                        <Link
                          href={`/products?categoryId=${item.id}`}
                          className="card inline-flex items-center gap-2 px-4 py-2 text-sm text-slate-700 hover:border-brand-500 hover:text-brand-600"
                        >
                          {item.name}
                          <span className="text-xs text-slate-400">{item.count}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
              {bestsellers && bestsellers.content.length > 0 && (
                <section aria-labelledby="bestsellers">
                  <h2 id="bestsellers" className="mb-3 text-lg font-semibold text-slate-900">
                    {t("bestsellers")}
                  </h2>
                  <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                    {bestsellers.content.map((product) => (
                      <ProductCard key={product.id} product={product} />
                    ))}
                  </div>
                </section>
              )}
            </div>
          ) : products.content.length === 0 ? (
            <EmptyState title={t("empty")}>
              <Link href="/products" className="link text-sm">
                {t("viewAll")}
              </Link>
            </EmptyState>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-4 md:grid-cols-3">
                {products.content.map((product) => (
                  <ProductCard key={product.id} product={product} />
                ))}
              </div>
              <Pagination page={products.page} totalPages={products.totalPages} basePath="/products" params={current} />
            </>
          )}
        </section>
      </div>
    </div>
  );
}
