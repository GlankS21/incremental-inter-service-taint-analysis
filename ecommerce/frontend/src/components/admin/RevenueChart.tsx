"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { INTL_LOCALE } from "@/i18n/config";
import { formatMoney } from "@/lib/format";
import type { DailyStats } from "@/lib/types";

const HEIGHT = 220;
const PADDING = { top: 12, right: 8, bottom: 28, left: 56 };
const MAX_BAR = 24;
const TICKS = 4;

/** "2026-10-21" → "21.10" (the date is a calendar day of the shop, no time zone shift). */
function shortDay(date: string): string {
  const [, month, day] = date.split("-");
  return `${day}.${month}`;
}

function fullDay(date: string): string {
  const [year, month, day] = date.split("-");
  return `${day}.${month}.${year}`;
}

/** Rounds the top of the axis up to a clean number (1, 2, 2.5 or 5 × 10^n). */
function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((candidate) => candidate * power >= value) ?? 10;
  return step * power;
}

const compact = new Intl.NumberFormat(INTL_LOCALE, { notation: "compact", maximumFractionDigits: 1 });

/**
 * Daily revenue as columns of one hue (a single series: the title names it, no legend). Hovering or
 * focusing a day shows its exact amount; the same numbers are available as a table below the chart.
 */
export function RevenueChart({ daily, currency }: { daily: DailyStats[]; currency: string }) {
  const t = useTranslations("dashboard");
  const [active, setActive] = useState<number | null>(null);
  const width = Math.max(320, daily.length * 18 + PADDING.left + PADDING.right);
  const plotWidth = width - PADDING.left - PADDING.right;
  const plotHeight = HEIGHT - PADDING.top - PADDING.bottom;
  const max = niceMax(Math.max(0, ...daily.map((day) => day.revenue)));
  const band = plotWidth / Math.max(daily.length, 1);
  const barWidth = Math.min(MAX_BAR, Math.max(2, band * 0.7));
  // At most ~10 date labels on the axis
  const labelEvery = Math.max(1, Math.ceil(daily.length / 10));
  const y = (value: number) => PADDING.top + plotHeight - (value / max) * plotHeight;
  const hovered = active === null ? null : daily[active];

  return (
    <div>
      <div className="relative overflow-x-auto">
        <svg
          viewBox={`0 0 ${width} ${HEIGHT}`}
          className="h-auto w-full min-w-80"
          role="img"
          aria-label={t("revenueChart")}
          onMouseLeave={() => setActive(null)}
        >
          {Array.from({ length: TICKS + 1 }, (_, index) => {
            const value = (max / TICKS) * index;
            return (
              <g key={index}>
                <line x1={PADDING.left} x2={width - PADDING.right} y1={y(value)} y2={y(value)} stroke="#e2e8f0" strokeWidth={1} />
                <text x={PADDING.left - 8} y={y(value) + 4} textAnchor="end" className="fill-slate-500 text-[11px]">
                  {compact.format(value)}
                </text>
              </g>
            );
          })}
          {daily.map((day, index) => {
            const x = PADDING.left + band * index;
            const top = y(day.revenue);
            const height = PADDING.top + plotHeight - top;
            const radius = Math.min(4, barWidth / 2, height);
            const left = x + (band - barWidth) / 2;
            const bottom = PADDING.top + plotHeight;
            return (
              <g key={day.date}>
                {height > 0 && (
                  // Rounded at the data end, square at the baseline
                  <path
                    d={`M${left},${bottom} V${top + radius} Q${left},${top} ${left + radius},${top} H${left + barWidth - radius} Q${left + barWidth},${top} ${left + barWidth},${top + radius} V${bottom} Z`}
                    className={active === null || active === index ? "fill-brand-500" : "fill-brand-500/40"}
                  />
                )}
                {index % labelEvery === 0 && (
                  <text x={x + band / 2} y={HEIGHT - 8} textAnchor="middle" className="fill-slate-500 text-[11px]">
                    {shortDay(day.date)}
                  </text>
                )}
                {/* Hit target: the whole column band, larger than the bar */}
                <rect
                  x={x}
                  y={PADDING.top}
                  width={band}
                  height={plotHeight}
                  fill="transparent"
                  tabIndex={0}
                  aria-label={t("dayTooltip", {
                    date: fullDay(day.date),
                    revenue: formatMoney(day.revenue, currency),
                    orders: day.paidOrders,
                  })}
                  onMouseEnter={() => setActive(index)}
                  onFocus={() => setActive(index)}
                  onBlur={() => setActive(null)}
                  className="outline-none"
                />
              </g>
            );
          })}
          <line
            x1={PADDING.left}
            x2={width - PADDING.right}
            y1={PADDING.top + plotHeight}
            y2={PADDING.top + plotHeight}
            stroke="#94a3b8"
            strokeWidth={1}
          />
        </svg>
        {hovered && active !== null && (
          <div
            className="pointer-events-none absolute top-0 z-10 -translate-x-1/2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs whitespace-nowrap shadow-md"
            style={{ left: `${((PADDING.left + band * (active + 0.5)) / width) * 100}%` }}
          >
            <p className="font-medium text-slate-900">{fullDay(hovered.date)}</p>
            <p className="text-slate-700">{formatMoney(hovered.revenue, currency)}</p>
            <p className="text-slate-500">{t("paidOrders")}: {hovered.paidOrders}</p>
          </div>
        )}
      </div>
      <details className="mt-2 text-sm">
        <summary className="cursor-pointer text-xs text-slate-500">{t("tableView")}</summary>
        <div className="mt-2 max-h-64 overflow-auto">
          <table className="table">
            <thead>
              <tr>
                <th>{t("date")}</th>
                <th className="text-right">{t("orders")}</th>
                <th className="text-right">{t("paidOrders")}</th>
                <th className="text-right">{t("revenue")}</th>
              </tr>
            </thead>
            <tbody>
              {daily.map((day) => (
                <tr key={day.date}>
                  <td>{fullDay(day.date)}</td>
                  <td className="text-right">{day.orders}</td>
                  <td className="text-right">{day.paidOrders}</td>
                  <td className="text-right whitespace-nowrap">{formatMoney(day.revenue, currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
