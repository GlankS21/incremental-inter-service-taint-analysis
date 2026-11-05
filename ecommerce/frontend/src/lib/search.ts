import { oneOf } from "./search-params";
import type { BrandCount, CategoryCount, PopularSearches, PriceRangeCount, SearchSuggestions, SuggestProduct } from "./types";

/** Same limit as the API (longer keywords are answered with 400). */
export const MAX_QUERY_LENGTH = 100;

/** Delay between the last keystroke and the type-ahead request. */
export const SUGGEST_DEBOUNCE_MS = 250;

/** Trimmed, inner spaces collapsed, at most {@link MAX_QUERY_LENGTH} characters. */
export function normalizeQuery(value: string): string {
  return value.replace(/\s+/g, " ").trim().slice(0, MAX_QUERY_LENGTH).trim();
}

/** Results page of a keyword. */
export function searchHref(keyword: string): string {
  const value = normalizeQuery(keyword);
  return value ? `/products?keyword=${encodeURIComponent(value)}` : "/products";
}

// ---------------------------------------------------------------- highlighting

export interface HighlightSegment {
  text: string;
  match: boolean;
}

/**
 * Splits `text` into parts matching one of the words of `query` (case-insensitive, anywhere in a
 * word) and the rest, e.g. ("iPhone 15", "pho") → "iP", [pho], "ne 15".
 */
export function highlightSegments(text: string, query: string): HighlightSegment[] {
  const lowerText = text.toLowerCase();
  const words = normalizeQuery(query).toLowerCase().split(" ").filter(Boolean);
  // Lower-casing changes the length of a few characters (e.g. "İ"); offsets would be wrong then
  if (words.length === 0 || lowerText.length !== text.length) {
    return text ? [{ text, match: false }] : [];
  }
  const marked = new Array<boolean>(text.length).fill(false);
  for (const word of words) {
    for (let start = lowerText.indexOf(word); start !== -1; start = lowerText.indexOf(word, start + word.length)) {
      marked.fill(true, start, start + word.length);
    }
  }
  const segments: HighlightSegment[] = [];
  for (let index = 0; index < text.length; index++) {
    const last = segments[segments.length - 1];
    if (last && last.match === marked[index]) {
      last.text += text[index];
    } else {
      segments.push({ text: text[index], match: marked[index] });
    }
  }
  return segments;
}

// ---------------------------------------------------------------- recent searches

export const RECENT_SEARCHES_KEY = "techshop.recentSearches";
export const MAX_RECENT_SEARCHES = 8;

/** The part of the Web Storage API used here, so tests can pass a fake one. */
export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** localStorage, or null on the server or when the browser denies access to it. */
export function browserStorage(): KeyValueStorage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

/** Stored value → list of queries; anything malformed is ignored. */
export function parseRecentSearches(raw: string | null): string[] {
  if (!raw) {
    return [];
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value)) {
      return [];
    }
    return value
      .filter((item): item is string => typeof item === "string")
      .map(normalizeQuery)
      .filter(Boolean)
      .slice(0, MAX_RECENT_SEARCHES);
  } catch {
    return [];
  }
}

/** `query` first, without its earlier occurrences (compared case-insensitively), at most 8 entries. */
export function withRecentSearch(list: string[], query: string): string[] {
  const value = normalizeQuery(query);
  if (!value) {
    return list;
  }
  const key = value.toLowerCase();
  return [value, ...list.filter((item) => item.toLowerCase() !== key)].slice(0, MAX_RECENT_SEARCHES);
}

export function loadRecentSearches(storage: KeyValueStorage | null = browserStorage()): string[] {
  try {
    return parseRecentSearches(storage?.getItem(RECENT_SEARCHES_KEY) ?? null);
  } catch {
    return [];
  }
}

function storeRecentSearches(list: string[], storage: KeyValueStorage | null): void {
  try {
    if (list.length === 0) {
      storage?.removeItem(RECENT_SEARCHES_KEY);
    } else {
      storage?.setItem(RECENT_SEARCHES_KEY, JSON.stringify(list));
    }
  } catch {
    // Private mode or quota exceeded: the history is a convenience only
  }
}

/** Adds a submitted query; returns the new list. */
export function addRecentSearch(query: string, storage: KeyValueStorage | null = browserStorage()): string[] {
  const list = withRecentSearch(loadRecentSearches(storage), query);
  storeRecentSearches(list, storage);
  return list;
}

/** Removes a query (case-insensitively); returns the new list. */
export function removeRecentSearch(query: string, storage: KeyValueStorage | null = browserStorage()): string[] {
  const key = query.toLowerCase();
  const list = loadRecentSearches(storage).filter((item) => item.toLowerCase() !== key);
  storeRecentSearches(list, storage);
  return list;
}

export function clearRecentSearches(storage: KeyValueStorage | null = browserStorage()): void {
  storeRecentSearches([], storage);
}

// ---------------------------------------------------------------- dropdown options

export type SearchOption =
  | { kind: "product"; product: SuggestProduct }
  | { kind: "category"; category: CategoryCount }
  | { kind: "brand"; brand: BrandCount }
  | { kind: "completion" | "recent" | "popular"; query: string }
  | { kind: "all"; query: string; total: number };

export type SearchGroupKey = "recent" | "popular" | "products" | "categories" | "brands" | "completions";

export interface SearchOptionGroup {
  key: SearchGroupKey;
  /** Options of the group with their position in the flat list (used for keyboard navigation). */
  options: { option: SearchOption; index: number }[];
}

export interface SearchOptionList {
  groups: SearchOptionGroup[];
  /** "Show all results" row, after the groups. */
  showAll: { option: SearchOption; index: number } | null;
  /** Every option in display order. */
  options: SearchOption[];
}

/**
 * Options of the dropdown: the history and popular queries while the input is empty, otherwise the
 * suggestions of the API.
 */
export function buildSearchOptions(
  query: string,
  suggestions: SearchSuggestions | null,
  recent: string[],
  popular: PopularSearches | null,
): SearchOptionList {
  const options: SearchOption[] = [];
  const groups: SearchOptionGroup[] = [];
  const group = (key: SearchGroupKey, items: SearchOption[]) => {
    if (items.length > 0) {
      groups.push({ key, options: items.map((option) => ({ option, index: options.push(option) - 1 })) });
    }
  };

  if (!normalizeQuery(query)) {
    group("recent", recent.map((item) => ({ kind: "recent", query: item })));
    const recentKeys = new Set(recent.map((item) => item.toLowerCase()));
    group(
      "popular",
      (popular?.queries ?? []).filter((item) => !recentKeys.has(item.toLowerCase())).map((item) => ({ kind: "popular", query: item })),
    );
    return { groups, showAll: null, options };
  }
  if (!suggestions) {
    return { groups, showAll: null, options };
  }
  group("products", suggestions.products.map((product) => ({ kind: "product", product })));
  group("categories", suggestions.categories.map((category) => ({ kind: "category", category })));
  group("brands", suggestions.brands.map((brand) => ({ kind: "brand", brand })));
  group("completions", suggestions.completions.map((item) => ({ kind: "completion", query: item })));
  let showAll: SearchOptionList["showAll"] = null;
  if (suggestions.totalProducts > 0) {
    const option: SearchOption = { kind: "all", query: suggestions.correctedQuery ?? suggestions.query, total: suggestions.totalProducts };
    showAll = { option, index: options.push(option) - 1 };
  }
  return { groups, showAll, options };
}

/** Page opened by an option. */
export function optionHref(option: SearchOption): string {
  switch (option.kind) {
    case "product":
      return `/products/${encodeURIComponent(option.product.slug)}`;
    case "category":
      return `/products?categoryId=${option.category.id}`;
    case "brand":
      return `/products?brand=${encodeURIComponent(option.brand.name)}`;
    default:
      return searchHref(option.query);
  }
}

/** The query saved in the history when the option is chosen, or null for products, categories, brands. */
export function optionSearchQuery(option: SearchOption): string | null {
  return "query" in option ? option.query : null;
}

/**
 * Index of the active option after pressing ↓ (delta 1) or ↑ (delta -1), wrapping around; -1 means
 * none (the input itself). From none, ↓ goes to the first option and ↑ to the last one.
 */
export function moveActiveIndex(current: number, delta: 1 | -1, count: number): number {
  if (count <= 0) {
    return -1;
  }
  if (current < 0 || current >= count) {
    return delta > 0 ? 0 : count - 1;
  }
  return (current + delta + count) % count;
}

// ---------------------------------------------------------------- catalogue filters

/** Values of the catalogue's `sort` parameter. */
export const CATALOG_SORTS = [
  "relevance",
  "salesCount,desc",
  "createdAt,desc",
  "price,asc",
  "price,desc",
  "name,asc",
  "ratingAverage,desc",
] as const;

export type CatalogSort = (typeof CATALOG_SORTS)[number];

/** Sorts offered in the select: relevance only for a keyword search. */
export function catalogSortOptions(hasKeyword: boolean): CatalogSort[] {
  return CATALOG_SORTS.filter((sort) => hasKeyword || sort !== "relevance");
}

/** The requested sort if it is offered, else the default (relevance for a search, newest first otherwise). */
export function catalogSort(value: string | undefined, hasKeyword: boolean): CatalogSort {
  return oneOf(value, catalogSortOptions(hasKeyword)) ?? (hasKeyword ? "relevance" : "createdAt,desc");
}

/** URL filter of a price bucket (maxPrice is inclusive in the API, fine for the UI). */
export function priceRangeFilter(range: Pick<PriceRangeCount, "from" | "to">): { minPrice?: number; maxPrice?: number } {
  return { minPrice: range.from ?? undefined, maxPrice: range.to ?? undefined };
}

/** The current minPrice/maxPrice are exactly those of the bucket. */
export function isPriceRangeActive(
  range: Pick<PriceRangeCount, "from" | "to">,
  minPrice: number | undefined,
  maxPrice: number | undefined,
): boolean {
  const filter = priceRangeFilter(range);
  return filter.minPrice === minPrice && filter.maxPrice === maxPrice;
}

// ---------------------------------------------------------------- synonyms (admin)

/** "Смартфон, телефон,, мобильник " → ["смартфон", "телефон", "мобильник"]: lower-case, de-duplicated. */
export function parseSynonymTerms(text: string): string[] {
  const terms = text
    .split(",")
    .map((term) => term.replace(/\s+/g, " ").trim().toLowerCase())
    .filter(Boolean);
  return [...new Set(terms)];
}
