import messages from "../../messages/ru.json";

/** Decline reasons of YooKassa and payment-service that have a translated explanation. */
export type PaymentFailureCode = keyof typeof messages.paymentFailure;

export function isKnownFailure(code: string | null | undefined): code is PaymentFailureCode {
  return !!code && Object.hasOwn(messages.paymentFailure, code);
}
