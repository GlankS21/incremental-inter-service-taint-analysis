import { useTranslations } from "next-intl";
import { INTL_LOCALE } from "@/i18n/config";

const SIZES = { sm: "h-3.5 w-3.5", md: "h-4 w-4", lg: "h-5 w-5" } as const;

const STAR = "M10 1.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L10 14.8l-5.2 2.8 1-5.8L1.5 7.7l5.9-.8L10 1.5z";

/** Five stars filled up to {@link value} (fractions shown as partly filled stars). */
export function RatingStars({ value, size = "md" }: { value: number; size?: keyof typeof SIZES }) {
  const t = useTranslations("reviews");
  const rounded = Math.round(value * 10) / 10;
  return (
    <span role="img" aria-label={t("ratingLabel", { value: rounded.toLocaleString(INTL_LOCALE) })} className="inline-flex gap-0.5">
      {[0, 1, 2, 3, 4].map((index) => {
        const fill = Math.max(0, Math.min(1, value - index));
        return (
          <span key={index} className={`relative ${SIZES[size]}`} aria-hidden="true">
            <svg viewBox="0 0 20 20" className="absolute inset-0 h-full w-full text-slate-300" fill="currentColor">
              <path d={STAR} />
            </svg>
            {fill > 0 && (
              <span className="absolute inset-0 overflow-hidden" style={{ width: `${fill * 100}%` }}>
                <svg viewBox="0 0 20 20" className={`${SIZES[size]} text-amber-400`} fill="currentColor">
                  <path d={STAR} />
                </svg>
              </span>
            )}
          </span>
        );
      })}
    </span>
  );
}
