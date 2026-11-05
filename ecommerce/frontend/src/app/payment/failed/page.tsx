import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { RetryPaymentButton } from "@/components/payment/RetryPaymentButton";
import { Alert, ButtonLink } from "@/components/ui";
import { formatMoney } from "@/lib/format";
import { awaitsOnlinePayment } from "@/lib/orders";
import { isKnownFailure } from "@/lib/payment-failure";
import { apiGet, apiGetOrNotFound } from "@/lib/server/data";
import { isUuid, param, type SearchParams } from "@/lib/search-params";
import type { Order, Payment } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("payment");
  return { title: t("metaFailed") };
}

export default async function PaymentFailedPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const orderId = param(await searchParams, "orderId");
  if (!orderId || !isUuid(orderId)) {
    notFound();
  }
  const [order, payment] = await Promise.all([
    apiGetOrNotFound<Order>(`/api/orders/${orderId}`, { auth: true }),
    apiGet<Payment>(`/api/payments/orders/${orderId}`, { auth: true }).catch(() => null),
  ]);
  if (order.status === "PAID" || order.status === "SHIPPED" || order.status === "DELIVERED") {
    redirect(`/payment/success?orderId=${orderId}`);
  }
  const t = await getTranslations("payment");
  const tFailure = await getTranslations("paymentFailure");
  const reason = payment?.failureReason ?? order.cancellationReason;
  const reasonText =
    isKnownFailure(payment?.failureReason)
      ? tFailure(payment.failureReason)
      : reason && order.status === "CANCELLED"
        ? reason
        : tFailure("unknown");
  const canRetry = awaitsOnlinePayment(order) && payment?.status === "FAILED";
  const stillOpen = awaitsOnlinePayment(order) && payment?.status === "PENDING";

  return (
    <div className="container-page py-12">
      <div className="card mx-auto flex max-w-lg flex-col items-center gap-4 p-8 text-center">
        <span
          aria-hidden="true"
          className="flex h-14 w-14 items-center justify-center rounded-full bg-rose-100 text-3xl text-rose-700"
        >
          ✕
        </span>
        <h1 className="text-2xl font-semibold text-slate-900">{t("failedTitle")}</h1>
        <p className="text-slate-600">
          {t("failedText", { number: order.orderNumber, amount: formatMoney(order.totalAmount, order.currency) })}
        </p>
        <Alert tone="danger">{reasonText}</Alert>
        {order.status === "CANCELLED" ? (
          <p className="text-sm text-slate-600">{t("orderCancelled")}</p>
        ) : (
          <>
            <p className="text-sm text-slate-600">{stillOpen ? t("unpaidHint") : t("failedHint")}</p>
            {payment && payment.attempts > 0 && (
              <p className="text-xs text-slate-500">{t("attemptsUsed", { count: payment.attempts })}</p>
            )}
          </>
        )}
        <div className="mt-2 flex w-full flex-col items-center gap-2">
          {canRetry && <RetryPaymentButton orderId={orderId} label={t("payAgain")} size="lg" />}
          {stillOpen && (
            <ButtonLink href={`/orders/${orderId}/payment`} size="lg">
              {t("resume")}
            </ButtonLink>
          )}
          <ButtonLink href={`/orders/${orderId}`} variant="ghost">
            {t("toOrder")}
          </ButtonLink>
        </div>
      </div>
    </div>
  );
}
