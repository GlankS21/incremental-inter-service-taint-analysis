import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { OrderPayment } from "@/components/payment/OrderPayment";
import { formatMoney } from "@/lib/format";
import { apiGetOrNotFound } from "@/lib/server/data";
import { isUuid } from "@/lib/search-params";
import type { Order } from "@/lib/types";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("payment");
  return { title: t("metaProcessing") };
}

/**
 * Online payment of an existing order, e.g. after leaving the checkout page or to try another card:
 * the same card form as on the checkout page.
 */
export default async function OrderPaymentPage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params;
  if (!isUuid(orderId)) {
    notFound();
  }
  const order = await apiGetOrNotFound<Order>(`/api/orders/${orderId}`, { auth: true });
  if (order.paymentMethod !== "ONLINE") {
    redirect(`/orders/${orderId}`);
  }
  if (order.status === "PAID" || order.status === "SHIPPED" || order.status === "DELIVERED") {
    redirect(`/payment/success?orderId=${orderId}`);
  }
  const t = await getTranslations("payment");
  const tOrder = await getTranslations("order");

  return (
    <div className="container-page py-10">
      <Link href={`/orders/${orderId}`} className="link text-sm">
        {tOrder("title", { number: order.orderNumber })}
      </Link>
      <div className="card mx-auto mt-4 flex max-w-lg flex-col gap-5 p-6 sm:p-8">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">{t("processingTitle")}</h1>
          <p className="mt-1 text-slate-600">
            {t("amountToPay", { amount: formatMoney(order.totalAmount, order.currency) })}
          </p>
        </div>
        <OrderPayment orderId={orderId} />
      </div>
    </div>
  );
}
