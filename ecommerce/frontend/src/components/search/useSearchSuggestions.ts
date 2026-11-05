"use client";

import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { api } from "@/lib/client/api";
import { ApiError } from "@/lib/errors";
import {
  addRecentSearch,
  browserStorage,
  clearRecentSearches,
  normalizeQuery,
  parseRecentSearches,
  RECENT_SEARCHES_KEY,
  removeRecentSearch,
  SUGGEST_DEBOUNCE_MS,
} from "@/lib/search";
import type { PopularSearches, SearchSuggestions } from "@/lib/types";

export type SuggestState =
  | { status: "idle" }
  /** `previous`: the last answer, still shown while the new one is on its way. */
  | { status: "loading"; previous: SearchSuggestions | null }
  | { status: "ready"; data: SearchSuggestions }
  | { status: "error"; tooManyRequests: boolean };

interface Answer {
  query: string;
  data: SearchSuggestions | null;
  error: unknown;
}

/**
 * Type-ahead suggestions of the input: requested 250 ms after the last keystroke, the previous
 * request is cancelled, and an answer for anything but the current query is ignored.
 */
export function useSearchSuggestions(input: string): SuggestState {
  const query = normalizeQuery(input);
  const [answer, setAnswer] = useState<Answer | null>(null);

  useEffect(() => {
    if (!query) {
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      api
        .get<SearchSuggestions>(`/products/suggest?q=${encodeURIComponent(query)}`, controller.signal)
        .then((data) => !controller.signal.aborted && setAnswer({ query, data, error: null }))
        .catch((error: unknown) => !controller.signal.aborted && setAnswer({ query, data: null, error }));
    }, SUGGEST_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  if (!query) {
    return { status: "idle" };
  }
  if (answer?.query !== query) {
    return { status: "loading", previous: answer?.data ?? null };
  }
  if (answer.data) {
    return { status: "ready", data: answer.data };
  }
  return { status: "error", tooManyRequests: answer.error instanceof ApiError && answer.error.status === 429 };
}

// The popular queries change slowly: one request per page load, shared by every search box
let popularRequest: Promise<PopularSearches | null> | null = null;

function loadPopularSearches(): Promise<PopularSearches | null> {
  popularRequest ??= api.get<PopularSearches>("/products/search/popular").catch(() => {
    // Try again the next time the dropdown opens
    popularRequest = null;
    return null;
  });
  return popularRequest;
}

/** Popular queries, loaded the first time `enabled` is true. */
export function usePopularSearches(enabled: boolean): PopularSearches | null {
  const [popular, setPopular] = useState<PopularSearches | null>(null);
  useEffect(() => {
    if (!enabled) {
      return;
    }
    let active = true;
    void loadPopularSearches().then((value) => active && value && setPopular(value));
    return () => {
      active = false;
    };
  }, [enabled]);
  return popular;
}

/** Fired after this tab changes the history ("storage" events only report other tabs). */
const RECENT_EVENT = "techshop:recent-searches";

function subscribeRecent(callback: () => void): () => void {
  window.addEventListener("storage", callback);
  window.addEventListener(RECENT_EVENT, callback);
  return () => {
    window.removeEventListener("storage", callback);
    window.removeEventListener(RECENT_EVENT, callback);
  };
}

function recentSnapshot(): string | null {
  try {
    return browserStorage()?.getItem(RECENT_SEARCHES_KEY) ?? null;
  } catch {
    return null;
  }
}

function notifyRecent() {
  window.dispatchEvent(new Event(RECENT_EVENT));
}

/** Search history kept in localStorage (empty during server rendering and when storage is blocked). */
export function useRecentSearches() {
  const raw = useSyncExternalStore(subscribeRecent, recentSnapshot, () => null);
  const recent = useMemo(() => parseRecentSearches(raw), [raw]);
  const add = useCallback((query: string) => {
    addRecentSearch(query);
    notifyRecent();
  }, []);
  const remove = useCallback((query: string) => {
    removeRecentSearch(query);
    notifyRecent();
  }, []);
  const clear = useCallback(() => {
    clearRecentSearches();
    notifyRecent();
  }, []);
  return { recent, add, remove, clear };
}
