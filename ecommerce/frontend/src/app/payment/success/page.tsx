import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { OrderAutoRefresh } from "@/components/order/OrderActions";
import { ButtonLink, Spinner } from "@/components/ui";
import { formatMoney } from "@/lib/format";
import { needsPaymentForm } from "@/lib/orders";
import { apiGet, apiGetOrNotFound } from "@/lib/server/data";
import { isUuid, param, type SearchParams } from "@/lib/search-params";
import type { Order, Payment } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("payment");
  return { title: t("metaSuccess") };
}

export default async function PaymentSuccessPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const orderId = param(await searchParams, "orderId");
  if (!orderId || !isUuid(orderId)) {
    notFound();
  }
  const order = await apiGetOrNotFound<Order>(`/api/orders/${orderId}`, { auth: true });
  if (order.status === "CANCELLED") {
    redirect(`/payment/failed?orderId=${orderId}`);
  }
  if (order.status === "AWAITING_PAYMENT") {
    // Opened by hand or before paying: only a captured payment is "received"
    const payment = await apiGet<Payment>(`/api/payments/orders/${orderId}`, { auth: true }).catch(() => null);
    if (needsPaymentForm(order, payment)) {
      redirect(`/orders/${orderId}/payment`);
    }
  }
  const t = await getTranslations("payment");
  // The payment is captured; order-service confirms the order a moment later
  const confirming = order.status === "PENDING" || order.status === "AWAITING_PAYMENT";

  return (
    <div className="container-page py-12">
      <OrderAutoRefresh active={confirming} />
      <div className="card mx-auto flex max-w-lg flex-col items-center gap-4 p-8 text-center">
        {confirming ? (
          <>
            <Spinner className="h-10 w-10 text-brand-600" />
            <p className="text-lg font-medium text-slate-900" role="status">
              {t("successPending", { number: order.orderNumber })}
            </p>
          </>
        ) : (
          <>
            <span
              aria-hidden="true"
              className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-3xl text-emerald-700"
            >
              ✓
            </span>
            <h1 className="text-2xl font-semibold text-slate-900">{t("successTitle")}</h1>
            <p className="text-slate-600">
              {t("successText", {
                number: order.orderNumber,
                amount: formatMoney(order.totalAmount, order.currency),
              })}
            </p>
          </>
        )}
        <div className="mt-2 flex flex-wrap justify-center gap-2">
          <ButtonLink href={`/orders/${orderId}`}>{t("toOrder")}</ButtonLink>
          <ButtonLink href="/products" variant="secondary">
            {t("continueShopping")}
          </ButtonLink>
        </div>
      </div>
    </div>
  );
}
