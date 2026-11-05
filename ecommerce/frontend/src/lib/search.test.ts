import { describe, expect, it } from "vitest";
import {
  addRecentSearch,
  buildSearchOptions,
  catalogSort,
  catalogSortOptions,
  clearRecentSearches,
  highlightSegments,
  isPriceRangeActive,
  type KeyValueStorage,
  loadRecentSearches,
  MAX_RECENT_SEARCHES,
  moveActiveIndex,
  normalizeQuery,
  optionHref,
  parseRecentSearches,
  parseSynonymTerms,
  priceRangeFilter,
  RECENT_SEARCHES_KEY,
  removeRecentSearch,
  searchHref,
} from "./search";
import type { SearchSuggestions } from "./types";

function memoryStorage(initial: Record<string, string> = {}): KeyValueStorage & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (key) => data[key] ?? null,
    setItem: (key, value) => {
      data[key] = value;
    },
    removeItem: (key) => {
      delete data[key];
    },
  };
}

const throwingStorage: KeyValueStorage = {
  getItem: () => {
    throw new Error("SecurityError");
  },
  setItem: () => {
    throw new Error("QuotaExceededError");
  },
  removeItem: () => {
    throw new Error("SecurityError");
  },
};

describe("normalizeQuery", () => {
  it("trims, collapses spaces and cuts at 100 characters", () => {
    expect(normalizeQuery("  iphone   15 ")).toBe("iphone 15");
    expect(normalizeQuery("a".repeat(150))).toHaveLength(100);
    expect(searchHref(" чехол  для iphone")).toBe("/products?keyword=%D1%87%D0%B5%D1%85%D0%BE%D0%BB%20%D0%B4%D0%BB%D1%8F%20iphone");
    expect(searchHref("   ")).toBe("/products");
  });
});

describe("highlightSegments", () => {
  it("marks the match case-insensitively", () => {
    expect(highlightSegments("Samsung Galaxy", "sam")).toEqual([
      { text: "Sam", match: true },
      { text: "sung Galaxy", match: false },
    ]);
  });

  it("marks matches in the middle of a word and every word of the query", () => {
    expect(highlightSegments("iPhone 15 Pro", "PHO pro")).toEqual([
      { text: "i", match: false },
      { text: "Pho", match: true },
      { text: "ne 15 ", match: false },
      { text: "Pro", match: true },
    ]);
  });

  it("works with Cyrillic and merges overlapping matches", () => {
    expect(highlightSegments("Ноутбук", "ноут утб")).toEqual([
      { text: "Ноутб", match: true },
      { text: "ук", match: false },
    ]);
  });

  it("returns the whole text unmarked without a match or query", () => {
    expect(highlightSegments("Наушники", "xyz")).toEqual([{ text: "Наушники", match: false }]);
    expect(highlightSegments("Наушники", "  ")).toEqual([{ text: "Наушники", match: false }]);
    expect(highlightSegments("", "a")).toEqual([]);
  });
});

describe("recent searches", () => {
  it("puts the newest first and removes duplicates case-insensitively", () => {
    const storage = memoryStorage();
    addRecentSearch("iphone", storage);
    addRecentSearch("ноутбук", storage);
    expect(addRecentSearch("  IPhone ", storage)).toEqual(["IPhone", "ноутбук"]);
    expect(loadRecentSearches(storage)).toEqual(["IPhone", "ноутбук"]);
  });

  it("keeps at most 8 queries", () => {
    const storage = memoryStorage();
    for (let index = 1; index <= 10; index++) {
      addRecentSearch(`query ${index}`, storage);
    }
    const list = loadRecentSearches(storage);
    expect(list).toHaveLength(MAX_RECENT_SEARCHES);
    expect(list[0]).toBe("query 10");
    expect(list[7]).toBe("query 3");
  });

  it("ignores blank queries", () => {
    const storage = memoryStorage();
    expect(addRecentSearch("   ", storage)).toEqual([]);
    expect(storage.data).toEqual({});
  });

  it("removes one query and clears the history", () => {
    const storage = memoryStorage();
    addRecentSearch("a", storage);
    addRecentSearch("b", storage);
    expect(removeRecentSearch("A", storage)).toEqual(["b"]);
    clearRecentSearches(storage);
    expect(storage.data[RECENT_SEARCHES_KEY]).toBeUndefined();
    expect(loadRecentSearches(storage)).toEqual([]);
  });

  it("ignores malformed stored values", () => {
    expect(parseRecentSearches("{")).toEqual([]);
    expect(parseRecentSearches('{"a":1}')).toEqual([]);
    expect(parseRecentSearches('["ok", 3, null, "  "]')).toEqual(["ok"]);
  });

  it("never throws when the storage is unavailable", () => {
    expect(loadRecentSearches(throwingStorage)).toEqual([]);
    expect(addRecentSearch("iphone", throwingStorage)).toEqual(["iphone"]);
    expect(removeRecentSearch("iphone", throwingStorage)).toEqual([]);
    expect(() => clearRecentSearches(throwingStorage)).not.toThrow();
    expect(loadRecentSearches(null)).toEqual([]);
  });
});

describe("moveActiveIndex", () => {
  it("enters the list from the input", () => {
    expect(moveActiveIndex(-1, 1, 5)).toBe(0);
    expect(moveActiveIndex(-1, -1, 5)).toBe(4);
  });

  it("moves and wraps around", () => {
    expect(moveActiveIndex(0, 1, 5)).toBe(1);
    expect(moveActiveIndex(4, 1, 5)).toBe(0);
    expect(moveActiveIndex(0, -1, 5)).toBe(4);
  });

  it("handles empty and shrunk lists", () => {
    expect(moveActiveIndex(2, 1, 0)).toBe(-1);
    expect(moveActiveIndex(7, 1, 3)).toBe(0);
  });
});

describe("buildSearchOptions", () => {
  const suggestions: SearchSuggestions = {
    query: "ntktajy",
    correctedQuery: "телефон",
    products: [
      {
        id: 1,
        name: "Телефон X",
        slug: "telefon-x",
        brand: null,
        thumbnailUrl: null,
        price: 100,
        compareAtPrice: null,
        currency: "RUB",
        inStock: true,
      },
    ],
    categories: [{ id: 3, name: "Смартфоны", slug: "smartfony", count: 4 }],
    brands: [{ name: "Apple & Co", count: 2 }],
    completions: ["телефон samsung"],
    totalProducts: 12,
  };

  it("numbers options across groups and adds the show-all row", () => {
    const list = buildSearchOptions("ntktajy", suggestions, ["old"], null);
    expect(list.groups.map((group) => group.key)).toEqual(["products", "categories", "brands", "completions"]);
    expect(list.groups.flatMap((group) => group.options.map((item) => item.index))).toEqual([0, 1, 2, 3]);
    expect(list.showAll).toEqual({ option: { kind: "all", query: "телефон", total: 12 }, index: 4 });
    expect(list.options).toHaveLength(5);
    expect(list.options.map(optionHref)).toEqual([
      "/products/telefon-x",
      "/products?categoryId=3",
      "/products?brand=Apple%20%26%20Co",
      "/products?keyword=%D1%82%D0%B5%D0%BB%D0%B5%D1%84%D0%BE%D0%BD%20samsung",
      "/products?keyword=%D1%82%D0%B5%D0%BB%D0%B5%D1%84%D0%BE%D0%BD",
    ]);
  });

  it("shows history and popular queries for an empty input", () => {
    const list = buildSearchOptions(" ", suggestions, ["iPhone"], { queries: ["iphone", "ноутбук"], categories: [] });
    expect(list.groups.map((group) => group.key)).toEqual(["recent", "popular"]);
    expect(list.options).toEqual([
      { kind: "recent", query: "iPhone" },
      { kind: "popular", query: "ноутбук" },
    ]);
    expect(list.showAll).toBeNull();
  });

  it("has no show-all row without results", () => {
    expect(buildSearchOptions("zzz", { ...suggestions, products: [], totalProducts: 0 }, [], null).showAll).toBeNull();
  });
});

describe("catalogue sort", () => {
  it("offers relevance only for a keyword search", () => {
    expect(catalogSortOptions(true)[0]).toBe("relevance");
    expect(catalogSortOptions(false)).not.toContain("relevance");
    expect(catalogSortOptions(false)).toContain("salesCount,desc");
  });

  it("validates the parameter and picks the default", () => {
    expect(catalogSort(undefined, true)).toBe("relevance");
    expect(catalogSort(undefined, false)).toBe("createdAt,desc");
    expect(catalogSort("relevance", false)).toBe("createdAt,desc");
    expect(catalogSort("salesCount,desc", false)).toBe("salesCount,desc");
    expect(catalogSort("price,asc", true)).toBe("price,asc");
    expect(catalogSort("stock,desc", true)).toBe("relevance");
  });
});

describe("price buckets", () => {
  it("maps a bucket to minPrice / maxPrice", () => {
    expect(priceRangeFilter({ from: null, to: 5000 })).toEqual({ minPrice: undefined, maxPrice: 5000 });
    expect(priceRangeFilter({ from: 5000, to: 15000 })).toEqual({ minPrice: 5000, maxPrice: 15000 });
    expect(priceRangeFilter({ from: 100000, to: null })).toEqual({ minPrice: 100000, maxPrice: undefined });
  });

  it("recognises the selected bucket", () => {
    expect(isPriceRangeActive({ from: 5000, to: 15000 }, 5000, 15000)).toBe(true);
    expect(isPriceRangeActive({ from: 100000, to: null }, 100000, undefined)).toBe(true);
    expect(isPriceRangeActive({ from: null, to: 5000 }, 0, 5000)).toBe(false);
    expect(isPriceRangeActive({ from: 5000, to: 15000 }, 5000, undefined)).toBe(false);
  });
});

describe("parseSynonymTerms", () => {
  it("splits on commas, lower-cases and removes duplicates and blanks", () => {
    expect(parseSynonymTerms(" Смартфон,  телефон ,, мобильный   телефон, ТЕЛЕФОН ")).toEqual([
      "смартфон",
      "телефон",
      "мобильный телефон",
    ]);
    expect(parseSynonymTerms(" , ")).toEqual([]);
  });
});
