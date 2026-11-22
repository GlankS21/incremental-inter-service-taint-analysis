import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { RevenueChart } from "@/components/admin/RevenueChart";
import { Alert, StatusBadge, Thumb } from "@/components/ui";
import { TIME_ZONE } from "@/i18n/config";
import { formatMoney, labelled, ORDER_STATUS_TONE } from "@/lib/format";
import { apiGet, query } from "@/lib/server/data";
import type { OrderStats, OrderStatus } from "@/lib/types";

export const STATS_PERIODS = [7, 30, 90] as const;
export type StatsPeriod = (typeof STATS_PERIODS)[number];

const STATUS_ORDER: OrderStatus[] = ["PENDING", "AWAITING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"];

/** yyyy-MM-dd of a day in the shop's time zone, `offset` days before today. */
function shopDate(offset: number): string {
  const day = new Date(Date.now() - offset * 86_400_000);
  // The sv-SE locale formats dates as yyyy-MM-dd
  return new Intl.DateTimeFormat("sv-SE", { timeZone: TIME_ZONE }).format(day);
}

function displayDate(date: string): string {
  const [year, month, day] = date.split("-");
  return `${day}.${month}.${year}`;
}

/** Revenue, orders and best sellers of the last `days` days (order-service statistics). */
export async function SalesStats({ days }: { days: StatsPeriod }) {
  const t = await getTranslations("dashboard");
  const tOrderStatus = await getTranslations("orderStatus");
  const orderStatus = labelled(ORDER_STATUS_TONE, (value) => tOrderStatus(value));
  const stats = await apiGet<OrderStats>(
    `/api/orders/admin/stats${query({ from: shopDate(days - 1), to: shopDate(0), top: 5 })}`,
    { auth: true },
  ).catch(() => null);

  const periods = (
    <nav aria-label={t("period")} className="flex gap-1 rounded-lg bg-slate-100 p-1">
      {STATS_PERIODS.map((period) => (
        <Link
          key={period}
          href={period === 30 ? "/admin" : `/admin?days=${period}`}
          aria-current={period === days ? "page" : undefined}
          className={`rounded-md px-3 py-1 text-sm whitespace-nowrap ${
            period === days ? "bg-white font-medium text-slate-900 shadow-sm" : "text-slate-600 hover:text-slate-900"
          }`}
        >
          {t("days", { count: period })}
        </Link>
      ))}
    </nav>
  );

  if (!stats) {
    return (
      <section className="mt-8 flex flex-col gap-4" aria-labelledby="stats-title">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="stats-title" className="text-lg font-semibold text-slate-900">
            {t("statsTitle")}
          </h2>
          {periods}
        </div>
        <Alert tone="warning">{t("statsUnavailable")}</Alert>
      </section>
    );
  }

  const kpis = [
    { label: t("revenue"), value: formatMoney(stats.revenue, stats.currency) },
    { label: t("paidOrders"), value: stats.paidOrderCount.toString() },
    { label: t("averageOrder"), value: formatMoney(stats.averageOrderValue, stats.currency) },
    { label: t("orders"), value: stats.orderCount.toString() },
    { label: t("cancelled"), value: stats.cancelledOrderCount.toString() },
  ];
  const statusTotal = STATUS_ORDER.reduce((sum, status) => sum + (stats.ordersByStatus[status] ?? 0), 0);

  return (
    <section className="mt-8 flex flex-col gap-4" aria-labelledby="stats-title">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 id="stats-title" className="text-lg font-semibold text-slate-900">
            {t("statsTitle")}
          </h2>
          <p className="text-xs text-slate-500">{t("range", { from: displayDate(stats.from), to: displayDate(stats.to) })}</p>
        </div>
        {periods}
      </div>

      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
        {kpis.map((kpi) => (
          <div key={kpi.label} className="card p-4">
            <dt className="text-sm text-slate-500">{kpi.label}</dt>
            <dd className="mt-1 text-xl font-semibold break-words text-slate-900">{kpi.value}</dd>
          </div>
        ))}
      </dl>

      <div className="card p-5">
        <h3 className="font-semibold text-slate-900">{t("revenueChart")}</h3>
        <p className="mb-3 text-xs text-slate-500">{t("revenueChartHint")}</p>
        <RevenueChart daily={stats.daily} currency={stats.currency} />
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_1.4fr]">
        <div className="card p-5">
          <h3 className="font-semibold text-slate-900">{t("byStatus")}</h3>
          <p className="mb-3 text-xs text-slate-500">{t("byStatusHint")}</p>
          <ul className="flex flex-col gap-2.5">
            {STATUS_ORDER.map((status) => {
              const count = stats.ordersByStatus[status] ?? 0;
              return (
                <li key={status} className="flex items-center gap-3 text-sm">
                  <span className="w-40 shrink-0">
                    <StatusBadge value={status} labels={orderStatus} />
                  </span>
                  <span className="h-2 flex-1 overflow-hidden rounded-full bg-slate-100" aria-hidden="true">
                    <span
                      className="block h-full rounded-full bg-slate-400"
                      style={{ width: `${statusTotal > 0 ? (count / statusTotal) * 100 : 0}%` }}
                    />
                  </span>
                  <Link href={`/admin/orders?status=${status}`} className="w-10 text-right font-medium text-slate-900 hover:text-brand-600">
                    {count}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>

        <div className="card overflow-x-auto">
          <h3 className="p-5 pb-0 font-semibold text-slate-900">{t("topProducts")}</h3>
          {stats.topProducts.length === 0 ? (
            <p className="p-5 text-sm text-slate-500">{t("noSales")}</p>
          ) : (
            <table className="table mt-2">
              <thead>
                <tr>
                  <th>{t("product")}</th>
                  <th className="text-right">{t("quantity")}</th>
                  <th className="text-right">{t("productRevenue")}</th>
                </tr>
              </thead>
              <tbody>
                {stats.topProducts.map((product) => (
                  <tr key={product.productId}>
                    <td>
                      <Link href={`/admin/products/${product.productId}`} className="flex items-center gap-3 hover:text-brand-600">
                        <Thumb src={product.thumbnailUrl} alt={product.productName} className="h-10 w-10" />
                        <span className="min-w-0">
                          <span className="line-clamp-2 font-medium">{product.productName}</span>
                          <span className="text-xs text-slate-500">{product.sku}</span>
                        </span>
                      </Link>
                    </td>
                    <td className="text-right">{product.quantity}</td>
                    <td className="text-right whitespace-nowrap">{formatMoney(product.revenue, stats.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </section>
  );
}
