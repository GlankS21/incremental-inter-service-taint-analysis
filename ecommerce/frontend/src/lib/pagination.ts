/** Query parameters of a list page, kept on every page link. */
export type PageParams = Record<string, string | number | undefined>;

/** Link to a page of a list: `target` is zero-based as in the API, 1-based in the URL; page 1 has no parameter. */
export function pageHref(basePath: string, params: PageParams, target: number): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "" && key !== "page") {
      search.set(key, String(value));
    }
  }
  if (target > 0) {
    search.set("page", String(target + 1));
  }
  const text = search.toString();
  return text ? `${basePath}?${text}` : basePath;
}

/**
 * Where a page past the end of a list (an old link, "?page=999") goes: the last page, instead of an
 * empty list. Null when the page exists or the list is empty.
 */
export function lastPageRedirect(page: number, totalPages: number, basePath: string, params: PageParams): string | null {
  return totalPages > 0 && page >= totalPages ? pageHref(basePath, params, totalPages - 1) : null;
}
