"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useRef, useState, type ChangeEvent, type KeyboardEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Price } from "@/components/Price";
import { Badge, Spinner, Thumb } from "@/components/ui";
import {
  buildSearchOptions,
  highlightSegments,
  MAX_QUERY_LENGTH,
  moveActiveIndex,
  normalizeQuery,
  optionHref,
  optionSearchQuery,
  searchHref,
  type SearchGroupKey,
  type SearchOption,
  type SearchOptionList,
} from "@/lib/search";
import { usePopularSearches, useRecentSearches, useSearchSuggestions, type SuggestState } from "./useSearchSuggestions";

const GROUP_LABELS = {
  recent: "groupRecent",
  popular: "groupPopular",
  products: "groupProducts",
  categories: "groupCategories",
  brands: "groupBrands",
  completions: "groupCompletions",
} as const satisfies Record<SearchGroupKey, string>;

interface ControllerOptions {
  /** Full-screen mobile search: the list is always shown and Esc is handled by the dialog. */
  overlay?: boolean;
  /** Called after navigating to a result. */
  onNavigate?: () => void;
}

/** State and behaviour of a search input with its dropdown, shared by the header box and the mobile overlay. */
function useSearchController({ overlay = false, onNavigate }: ControllerOptions) {
  const router = useRouter();
  const baseId = useId();
  const [value, setValue] = useState("");
  const [open, setOpen] = useState(false);
  const expanded = overlay || open;
  // Nothing is requested while the list is closed (e.g. the submitted query left in the input)
  const suggest = useSearchSuggestions(expanded ? value : "");
  const { recent, add, remove, clear } = useRecentSearches();
  const empty = !normalizeQuery(value);
  const popular = usePopularSearches(expanded && empty);
  const data = suggest.status === "ready" ? suggest.data : suggest.status === "loading" ? suggest.previous : null;
  const list = useMemo(() => buildSearchOptions(value, data, recent, popular), [value, data, recent, popular]);
  // The active option belongs to one list: a new list (new answer, new history) starts without one
  const [activeState, setActiveState] = useState<{ options: SearchOption[]; index: number }>({ options: [], index: -1 });
  const active = activeState.options === list.options ? activeState.index : -1;
  const setActive = (index: number) => setActiveState({ options: list.options, index });

  const status = statusOf(suggest, empty, list);
  const visible = expanded && (list.options.length > 0 || status !== null);

  const go = (href: string, query: string | null) => {
    if (query) {
      add(query);
      setValue(query);
    }
    setOpen(false);
    setActive(-1);
    onNavigate?.();
    router.push(href);
  };
  const choose = (option: SearchOption) => go(optionHref(option), optionSearchQuery(option));
  const submit = () => {
    const query = normalizeQuery(value);
    go(searchHref(query), query || null);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case "ArrowDown":
      case "ArrowUp":
        event.preventDefault();
        if (!expanded) {
          setOpen(true);
        } else {
          setActive(moveActiveIndex(active, event.key === "ArrowDown" ? 1 : -1, list.options.length));
        }
        break;
      case "Enter":
        event.preventDefault();
        if (visible && active >= 0) {
          choose(list.options[active]);
        } else {
          submit();
        }
        break;
      case "Escape":
        if (overlay) {
          break;
        }
        // First Esc closes the list, the second one clears the input
        if (visible) {
          event.preventDefault();
          setOpen(false);
          setActive(-1);
        } else if (value) {
          event.preventDefault();
          setValue("");
        }
        break;
      case "Delete": {
        // Shift+Delete removes the highlighted history entry (as in browsers' address bars)
        const option = list.options[active];
        if (event.shiftKey && option?.kind === "recent") {
          event.preventDefault();
          remove(option.query);
        }
        break;
      }
      case "Tab":
        setOpen(false);
        break;
    }
  };

  const listboxId = `${baseId}-listbox`;
  const optionId = (index: number) => `${baseId}-option-${index}`;

  return {
    baseId,
    value,
    visible,
    status,
    list,
    active,
    correctedQuery: !empty && suggest.status === "ready" ? suggest.data.correctedQuery : null,
    query: normalizeQuery(value),
    listboxId,
    optionId,
    setActive,
    choose,
    removeRecent: remove,
    clearRecent: clear,
    open: () => setOpen(true),
    close: () => setOpen(false),
    clearInput: () => setValue(""),
    inputProps: {
      role: "combobox",
      type: "text",
      value,
      maxLength: MAX_QUERY_LENGTH,
      autoComplete: "off",
      autoCorrect: "off",
      spellCheck: false,
      enterKeyHint: "search",
      "aria-autocomplete": "list",
      "aria-expanded": visible,
      "aria-controls": listboxId,
      "aria-activedescendant": visible && active >= 0 ? optionId(active) : undefined,
      onChange: (event: ChangeEvent<HTMLInputElement>) => {
        setValue(event.target.value);
        setOpen(true);
        setActive(-1);
      },
      onKeyDown,
    } as const,
  };
}

type SearchController = ReturnType<typeof useSearchController>;

type Status = "loading" | "error" | "tooManyRequests" | "noResults" | null;

/** Message line shown instead of (or above) the suggestions. */
function statusOf(suggest: SuggestState, empty: boolean, list: SearchOptionList): Status {
  if (empty) {
    return null;
  }
  switch (suggest.status) {
    case "loading":
      return list.options.length === 0 ? "loading" : null;
    case "error":
      return suggest.tooManyRequests ? "tooManyRequests" : "error";
    case "ready":
      return list.options.length === 0 ? "noResults" : null;
    default:
      return null;
  }
}

function Highlighted({ text, query }: { text: string; query: string }) {
  return (
    <>
      {highlightSegments(text, query).map((segment, index) =>
        segment.match ? (
          <mark key={index} className="bg-transparent font-semibold text-slate-900">
            {segment.text}
          </mark>
        ) : (
          <span key={index}>{segment.text}</span>
        ),
      )}
    </>
  );
}

function SearchIcon({ className = "h-4 w-4" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" strokeLinecap="round" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg className="h-4 w-4 shrink-0 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** Groups of options and the status line; a dropdown on desktop, the page body of the mobile overlay. */
function SearchResults({ controller, fullHeight = false }: { controller: SearchController; fullHeight?: boolean }) {
  const t = useTranslations("search");
  const { list, active, query, status, correctedQuery } = controller;

  const option = (item: SearchOption, index: number, content: ReactNode, extra?: ReactNode) => (
    <li
      key={index}
      id={controller.optionId(index)}
      role="option"
      aria-selected={index === active}
      // Keep the focus in the input; the click chooses the option
      onMouseDown={(event) => event.preventDefault()}
      onMouseMove={() => index !== active && controller.setActive(index)}
      onClick={() => controller.choose(item)}
      className={`flex cursor-pointer items-center gap-3 px-4 py-2 text-sm ${
        index === active ? "bg-brand-50 text-brand-700" : "text-slate-700"
      }`}
    >
      {content}
      {extra}
    </li>
  );

  const content = (item: SearchOption): ReactNode => {
    switch (item.kind) {
      case "product":
        return (
          <>
            <Thumb src={item.product.thumbnailUrl} alt="" className="h-10 w-10" />
            <span className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className="truncate">
                <Highlighted text={item.product.name} query={query} />
              </span>
              {item.product.brand && <span className="truncate text-xs text-slate-500">{item.product.brand}</span>}
            </span>
            <span className="flex shrink-0 flex-col items-end gap-1">
              <Price price={item.product.price} compareAtPrice={item.product.compareAtPrice} currency={item.product.currency} />
              {!item.product.inStock && <Badge>{t("outOfStock")}</Badge>}
            </span>
          </>
        );
      case "category":
      case "brand": {
        const entry = item.kind === "category" ? item.category : item.brand;
        return (
          <>
            <span className="min-w-0 flex-1 truncate">
              <Highlighted text={entry.name} query={query} />
            </span>
            <span className="shrink-0 text-xs text-slate-400">{t("count", { count: entry.count })}</span>
          </>
        );
      }
      case "recent":
        return (
          <>
            <ClockIcon />
            <span className="min-w-0 flex-1 truncate">{item.query}</span>
          </>
        );
      case "completion":
      case "popular":
        return (
          <>
            <SearchIcon className="h-4 w-4 shrink-0 text-slate-400" />
            <span className="min-w-0 flex-1 truncate">
              {item.kind === "completion" ? <Highlighted text={item.query} query={query} /> : item.query}
            </span>
          </>
        );
      case "all":
        return <span className="flex-1 font-medium text-brand-600">{t("showAll", { count: item.total })}</span>;
    }
  };

  const removeButton = (item: SearchOption) =>
    item.kind === "recent" && (
      <button
        type="button"
        tabIndex={-1}
        aria-label={t("removeRecent", { query: item.query })}
        onMouseDown={(event) => event.preventDefault()}
        onClick={(event) => {
          event.stopPropagation();
          controller.removeRecent(item.query);
        }}
        className="-mr-2 shrink-0 rounded px-2 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
      >
        ×
      </button>
    );

  const statusText =
    status === "loading" ? t("loading") : status === "noResults" ? t("noResults", { query }) : status ? t(status) : null;

  return (
    <div className={fullHeight ? "flex-1 overflow-y-auto pb-6" : "max-h-[70vh] overflow-y-auto py-2"}>
      {/* Announced to screen readers: progress, errors, the correction of the query */}
      <div aria-live="polite">
        {statusText && (
          <p className={`flex items-center gap-2 px-4 py-2 text-sm ${status === "loading" ? "text-slate-500" : "text-slate-600"}`}>
            {status === "loading" && <Spinner />}
            {statusText}
          </p>
        )}
        {correctedQuery && list.options.length > 0 && (
          <p className="px-4 py-2 text-sm text-slate-600">{t("corrected", { query: correctedQuery })}</p>
        )}
      </div>
      <ul id={controller.listboxId} role="listbox" aria-label={t("suggestions")}>
        {list.groups.map((group) => {
          const labelId = `${controller.baseId}-${group.key}`;
          return (
            <li key={group.key} role="presentation" className="py-1">
              <div role="presentation" className="flex items-center justify-between px-4 pt-2 pb-1">
                <span id={labelId} className="text-xs font-semibold tracking-wide text-slate-400 uppercase">
                  {t(GROUP_LABELS[group.key])}
                </span>
                {group.key === "recent" && (
                  <button
                    type="button"
                    tabIndex={-1}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={controller.clearRecent}
                    className="text-xs text-slate-500 hover:text-brand-600"
                  >
                    {t("clearHistory")}
                  </button>
                )}
              </div>
              <ul role="group" aria-labelledby={labelId}>
                {group.options.map(({ option: item, index }) => option(item, index, content(item), removeButton(item)))}
              </ul>
            </li>
          );
        })}
        {list.showAll && (
          <li role="presentation" className="mt-1 border-t border-slate-100 pt-1">
            <ul role="group">{option(list.showAll.option, list.showAll.index, content(list.showAll.option))}</ul>
          </li>
        )}
      </ul>
    </div>
  );
}

/** Inline search box of the header (from the sm breakpoint) with a type-ahead dropdown. */
export function SearchCombobox() {
  const t = useTranslations("search");
  const controller = useSearchController({});
  const inputRef = useRef<HTMLInputElement>(null);
  const hintId = `${controller.baseId}-hint`;

  return (
    <form
      role="search"
      className="relative hidden flex-1 sm:block"
      onSubmit={(event) => event.preventDefault()}
      // Closes when the focus leaves the box (outside click, Tab)
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          controller.close();
        }
      }}
    >
      <label htmlFor={`${controller.baseId}-input`} className="sr-only">
        {t("label")}
      </label>
      <span className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-slate-400">
        <SearchIcon />
      </span>
      <input
        {...controller.inputProps}
        ref={inputRef}
        id={`${controller.baseId}-input`}
        placeholder={t("placeholder")}
        aria-describedby={hintId}
        onFocus={controller.open}
        className="input rounded-full bg-slate-50 pr-9 pl-9"
      />
      <span id={hintId} className="sr-only">
        {t("keyboardHint")}
      </span>
      {controller.value && (
        <button
          type="button"
          aria-label={t("clearInput")}
          onClick={() => {
            controller.clearInput();
            inputRef.current?.focus();
          }}
          className="absolute top-1/2 right-2 -translate-y-1/2 rounded-full px-2 text-slate-400 hover:text-slate-700"
        >
          ×
        </button>
      )}
      {controller.visible && (
        <div className="absolute inset-x-0 top-full z-30 mt-1 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg">
          <SearchResults controller={controller} />
        </div>
      )}
    </form>
  );
}

/** Full-screen search of small screens. Rendered in <body>: the header's backdrop blur would trap a fixed element. */
function SearchOverlay({ onClose }: { onClose: () => void }) {
  const t = useTranslations("search");
  const controller = useSearchController({ overlay: true, onNavigate: onClose });
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t("dialog")}
      className="fixed inset-0 z-50 flex flex-col bg-white"
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          onClose();
        }
      }}
    >
      <form role="search" className="flex items-center gap-2 border-b border-slate-200 p-3" onSubmit={(event) => event.preventDefault()}>
        <button
          type="button"
          onClick={onClose}
          aria-label={t("close")}
          className="shrink-0 rounded-lg p-2 text-slate-600 hover:bg-slate-100"
        >
          <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <path d="M15 18l-6-6 6-6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <label htmlFor={`${controller.baseId}-input`} className="sr-only">
          {t("label")}
        </label>
        <input
          {...controller.inputProps}
          ref={inputRef}
          id={`${controller.baseId}-input`}
          placeholder={t("placeholder")}
          className="input flex-1 rounded-full bg-slate-50"
        />
        {controller.value && (
          <button
            type="button"
            aria-label={t("clearInput")}
            onClick={() => {
              controller.clearInput();
              inputRef.current?.focus();
            }}
            className="shrink-0 rounded-lg px-2 py-1 text-slate-400 hover:text-slate-700"
          >
            ×
          </button>
        )}
      </form>
      <SearchResults controller={controller} fullHeight />
    </div>,
    document.body,
  );
}

/** Search icon of small screens, opens {@link SearchOverlay}. */
export function MobileSearchButton() {
  const t = useTranslations("search");
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen(true)}
        aria-label={t("open")}
        aria-haspopup="dialog"
        aria-expanded={open}
        className="ml-auto rounded-lg p-2 text-slate-700 hover:bg-slate-100 sm:hidden"
      >
        <SearchIcon className="h-5 w-5" />
      </button>
      {open && (
        <SearchOverlay
          onClose={() => {
            setOpen(false);
            buttonRef.current?.focus();
          }}
        />
      )}
    </>
  );
}
