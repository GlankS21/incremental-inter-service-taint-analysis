import type { Order, OrderStatus, Payment } from "./types";

/** The saga is still running: stock reservation, then payment. */
export function isInProgress(status: OrderStatus): boolean {
  return status === "PENDING" || status === "AWAITING_PAYMENT";
}

/** An order can be cancelled until it is shipped; a paid order is refunded. */
export function isCancellable(status: OrderStatus): boolean {
  return isInProgress(status) || status === "PAID";
}

/** The customer still has to pay this order online. */
export function awaitsOnlinePayment(order: Order): boolean {
  return order.paymentMethod === "ONLINE" && order.status === "AWAITING_PAYMENT";
}

/**
 * An order waiting for its payment whose payment is not captured (still open, declined, or unknown):
 * the customer belongs on the payment form, not on the "payment received" page.
 */
export function needsPaymentForm(order: Order, payment: Payment | null): boolean {
  return order.status === "AWAITING_PAYMENT" && payment?.status !== "SUCCEEDED";
}

/** Something happens on its own soon: the page keeps refreshing while it does. */
export function isSettling(order: Order, payment: Payment | null): boolean {
  return isInProgress(order.status) || payment?.status === "REFUND_PENDING";
}
