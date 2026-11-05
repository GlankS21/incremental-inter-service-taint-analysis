import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { Pagination, pageIndex } from "@/components/Pagination";
import { PageTitle, StatusBadge } from "@/components/ui";
import { formatDateTime, formatMoney, labelled, PAYMENT_STATUS_TONE } from "@/lib/format";
import { isKnownFailure } from "@/lib/payment-failure";
import { apiGet, keepPageInRange, query } from "@/lib/server/data";
import { oneOf, param, type SearchParams } from "@/lib/search-params";
import type { PageResponse, Payment, PaymentStatus } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminPayments"))("metaTitle") };
}

const STATUSES = Object.keys(PAYMENT_STATUS_TONE) as PaymentStatus[];

/** Every payment with its provider, attempts and refund (payment-service). */
export default async function AdminPaymentsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const status = oneOf(param(params, "status"), STATUSES);
  const page = pageIndex(param(params, "page"));
  const [payments, t, tCommon, tStatus, tMethod, tFailure] = await Promise.all([
    apiGet<PageResponse<Payment>>(`/api/payments${query({ status, page, size: 20, sort: "createdAt,desc" })}`, { auth: true }),
    getTranslations("adminPayments"),
    getTranslations("common"),
    getTranslations("paymentStatus"),
    getTranslations("paymentMethod"),
    getTranslations("paymentFailure"),
  ]);
  keepPageInRange(page, payments, "/admin/payments", { status });
  const statuses = labelled(PAYMENT_STATUS_TONE, (value) => tStatus(value));

  return (
    <>
      <PageTitle>{t("title")}</PageTitle>
      <form method="get" className="mb-4 flex flex-wrap gap-2">
        <select name="status" defaultValue={status ?? ""} className="input w-auto" aria-label={t("status")}>
          <option value="">{tCommon("all")}</option>
          {STATUSES.map((value) => (
            <option key={value} value={value}>
              {tStatus(value)}
            </option>
          ))}
        </select>
        <button type="submit" className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
          {tCommon("search")}
        </button>
      </form>
      <div className="card overflow-x-auto">
        <table className="table">
          <thead>
            <tr>
              <th>{t("order")}</th>
              <th className="text-right">{t("amount")}</th>
              <th>{t("method")}</th>
              <th>{t("status")}</th>
              <th className="text-right">{t("attempts")}</th>
              <th>{t("created")}</th>
              <th>{t("paidAt")}</th>
            </tr>
          </thead>
          <tbody>
            {payments.content.map((payment) => (
              <tr key={payment.id}>
                <td>
                  <Link href={`/admin/orders/${payment.orderId}`} className="font-medium text-brand-700 hover:underline">
                    {payment.orderNumber}
                  </Link>
                </td>
                <td className="text-right whitespace-nowrap">{formatMoney(payment.amount, payment.currency)}</td>
                <td>
                  <p>{payment.method ? tMethod(payment.method) : "—"}</p>
                  {payment.provider && <p className="text-xs text-slate-500">{t(`providers.${payment.provider}`)}</p>}
                </td>
                <td>
                  <StatusBadge value={payment.status} labels={statuses} />
                  {payment.failureReason && (payment.status === "FAILED" || payment.status === "REFUND_FAILED") && (
                    <p className="mt-1 max-w-56 text-xs text-rose-700">
                      {isKnownFailure(payment.failureReason) ? tFailure(payment.failureReason) : payment.failureReason}
                    </p>
                  )}
                </td>
                <td className="text-right">{payment.attempts}</td>
                <td className="whitespace-nowrap text-slate-500">{formatDateTime(payment.createdAt)}</td>
                <td className="whitespace-nowrap text-slate-500">
                  {payment.paidAt ? formatDateTime(payment.paidAt) : "—"}
                  {payment.refundedAt && (
                    <p className="text-xs">
                      {t("refundedAt")}: {formatDateTime(payment.refundedAt)}
                    </p>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {payments.content.length === 0 && <p className="p-4 text-sm text-slate-500">{t("empty")}</p>}
      </div>
      <Pagination page={payments.page} totalPages={payments.totalPages} basePath="/admin/payments" params={{ status }} />
    </>
  );
}
