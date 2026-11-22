"use client";

import { useTranslations } from "next-intl";
import { useRef, useState, type KeyboardEvent } from "react";
import { MAX_RATING, ratingAfterKey } from "@/lib/reviews";

const SIZES = { md: "h-6 w-6", lg: "h-8 w-8" } as const;

const STAR = "M10 1.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L10 14.8l-5.2 2.8 1-5.8L1.5 7.7l5.9-.8L10 1.5z";

interface Props {
  /** 0 when nothing is chosen yet. */
  value: number;
  onChange: (rating: number) => void;
  /** Accessible name of the group, e.g. «Оценка» or «Оцените «Смартфон»». */
  label: string;
  size?: keyof typeof SIZES;
  /** Associates a visible label (aria-labelledby) instead of {@link label}. */
  labelledBy?: string;
}

/**
 * Star picker: a radio group operated with the mouse or the keyboard (arrows, Home/End, digits 1–5).
 * Only the chosen star (the first one when none is) is in the tab order.
 */
export function RatingInput({ value, onChange, label, size = "lg", labelledBy }: Props) {
  const t = useTranslations("reviews");
  const [hover, setHover] = useState(0);
  const stars = useRef<(HTMLButtonElement | null)[]>([]);
  const shown = hover || value;

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next = ratingAfterKey(value, event.key);
    if (next === null) {
      return;
    }
    event.preventDefault();
    onChange(next);
    stars.current[next - 1]?.focus();
  };

  return (
    <div
      role="radiogroup"
      aria-label={labelledBy ? undefined : label}
      aria-labelledby={labelledBy}
      className="inline-flex gap-1"
      onKeyDown={onKeyDown}
      onMouseLeave={() => setHover(0)}
    >
      {Array.from({ length: MAX_RATING }, (_, index) => index + 1).map((star) => {
        const checked = value === star;
        const focusable = checked || (value === 0 && star === 1);
        return (
          <button
            key={star}
            ref={(element) => {
              stars.current[star - 1] = element;
            }}
            type="button"
            role="radio"
            aria-checked={checked}
            aria-label={t("star", { count: star })}
            title={t("star", { count: star })}
            tabIndex={focusable ? 0 : -1}
            onClick={() => onChange(star)}
            onMouseEnter={() => setHover(star)}
            className="rounded focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:outline-none"
          >
            <svg
              viewBox="0 0 20 20"
              aria-hidden="true"
              className={`${SIZES[size]} transition-colors ${star <= shown ? "text-amber-400" : "text-slate-300"}`}
              fill="currentColor"
            >
              <path d={STAR} />
            </svg>
          </button>
        );
      })}
    </div>
  );
}
