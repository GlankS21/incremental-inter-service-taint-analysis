"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Alert, Button, ButtonLink, Spinner } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { ApiError } from "@/lib/errors";
import { isKnownFailure } from "@/lib/payment-failure";
import type { Order, Payment } from "@/lib/types";
import { MockCardForm } from "./MockCardForm";
import { YooKassaWidget } from "./YooKassaWidget";

const POLL_INTERVAL_MS = 2000;
/** After this, the customer is told that it takes longer than usual. */
const SLOW_AFTER_MS = 45_000;
/** Polling stops here; the customer can start it again. */
const GIVE_UP_AFTER_MS = 180_000;

type Step =
  /** Stock being reserved, payment being created at the provider. */
  | { kind: "preparing" }
  /** The card form (YooKassa widget or mock form) of the current attempt. */
  | { kind: "form"; payment: Payment }
  /** The form reported the end of the payment; the outcome is read from payment-service. */
  | { kind: "checking" }
  | { kind: "declined"; payment: Payment }
  | { kind: "cancelled"; order: Order };

/** Provider id of the attempt waiting for the card. */
function pendingCharge(payment: Payment): string | null {
  return (
    payment.transactions.findLast((transaction) => transaction.type === "CHARGE" && transaction.status === "PENDING")
      ?.providerId ?? null
  );
}

/**
 * Online payment of an order inside the shop's page: waits for the payment to be created, shows the
 * card form, then waits for the verified outcome. A declined card can be replaced by another one for
 * the same order.
 */
export function OrderPayment({ orderId }: { orderId: string }) {
  const t = useTranslations("payment");
  const tFailure = useTranslations("paymentFailure");
  const errorMessage = useErrorMessage();
  const router = useRouter();
  const [step, setStep] = useState<Step>({ kind: "preparing" });
  const [slow, setSlow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [round, setRound] = useState(0);

  const succeed = useCallback(() => router.replace(`/payment/success?orderId=${orderId}`), [router, orderId]);

  /** Applies the state of the order and its payment; true when the step is final for polling. */
  const apply = useCallback(
    (order: Order, payment: Payment | null, waitingFor: "token" | "outcome"): boolean => {
      if (order.status === "PAID" || payment?.status === "SUCCEEDED") {
        succeed();
        return true;
      }
      if (order.paymentMethod !== "ONLINE") {
        router.replace(`/orders/${orderId}`);
        return true;
      }
      if (order.status === "CANCELLED") {
        setStep({ kind: "cancelled", order });
        return true;
      }
      if (payment?.status === "FAILED") {
        setStep({ kind: "declined", payment });
        return true;
      }
      if (waitingFor === "token" && payment?.status === "PENDING" && payment.confirmationToken) {
        setStep({ kind: "form", payment });
        return true;
      }
      return false;
    },
    [orderId, router, succeed],
  );

  // Polls while preparing or checking
  const polling = step.kind === "preparing" || step.kind === "checking";
  const checking = step.kind === "checking";
  useEffect(() => {
    if (!polling) {
      return;
    }
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const started = Date.now();

    const loadPayment = async (): Promise<Payment | null> => {
      try {
        // After the form, ask payment-service to read the outcome from the provider: webhooks may not
        // reach a development machine
        return checking
          ? await api.post<Payment>(`/payments/orders/${orderId}/refresh`)
          : await api.get<Payment>(`/payments/orders/${orderId}`);
      } catch (caught) {
        // No payment yet while the stock is being reserved
        if (caught instanceof ApiError && caught.status === 404) {
          return null;
        }
        throw caught;
      }
    };

    const tick = async () => {
      try {
        const [order, payment] = await Promise.all([api.get<Order>(`/orders/${orderId}`), loadPayment()]);
        if (stopped) {
          return;
        }
        setError(null);
        if (apply(order, payment, checking ? "outcome" : "token")) {
          return;
        }
      } catch (caught) {
        if (stopped) {
          return;
        }
        setError(errorMessage(caught));
      }
      const elapsed = Date.now() - started;
      setSlow(elapsed > SLOW_AFTER_MS);
      if (elapsed < GIVE_UP_AFTER_MS) {
        timer = setTimeout(() => void tick(), POLL_INTERVAL_MS);
      }
    };

    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [polling, checking, orderId, round, apply, errorMessage]);

  const payWithAnotherCard = async () => {
    setRetrying(true);
    setError(null);
    try {
      const payment = await api.post<Payment>(`/payments/orders/${orderId}/retry`);
      if (payment.status === "PENDING" && payment.confirmationToken) {
        setStep({ kind: "form", payment });
      } else {
        setStep({ kind: "preparing" });
      }
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setRetrying(false);
    }
  };

  const toChecking = useCallback(() => setStep({ kind: "checking" }), []);

  const onMockResult = (payment: Payment) => {
    if (payment.status === "SUCCEEDED") {
      succeed();
    } else if (payment.status === "FAILED") {
      setStep({ kind: "declined", payment });
    } else {
      toChecking();
    }
  };

  return (
    <div className="flex flex-col gap-4">
      {step.kind === "form" && (
        <>
          {step.payment.provider === "MOCK" ? (
            <MockCardForm
              key={step.payment.confirmationToken}
              payment={step.payment}
              paymentId={pendingCharge(step.payment) ?? ""}
              onResult={onMockResult}
            />
          ) : (
            <YooKassaWidget
              key={step.payment.confirmationToken}
              confirmationToken={step.payment.confirmationToken ?? ""}
              onSuccess={toChecking}
              onFail={toChecking}
            />
          )}
          <p className="text-center text-xs text-slate-500">{t("securedBy")}</p>
        </>
      )}

      {(step.kind === "preparing" || step.kind === "checking") &&
        (slow ? (
          <div className="flex flex-col items-center gap-3 text-center">
            <Alert tone="warning">{t("slow")}</Alert>
            <div className="flex flex-wrap justify-center gap-2">
              <Button variant="secondary" onClick={() => setRound((value) => value + 1)}>
                {t("checkAgain")}
              </Button>
              <ButtonLink variant="ghost" href={`/orders/${orderId}`}>
                {t("toOrder")}
              </ButtonLink>
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-3 py-8 text-center" role="status" aria-live="polite">
            <Spinner className="h-10 w-10 text-brand-600" />
            <p className="text-lg font-medium text-slate-900">{t(step.kind)}</p>
            {step.kind === "checking" && <p className="text-sm text-slate-500">{t("checkingHint")}</p>}
          </div>
        ))}

      {step.kind === "declined" && (
        <div className="flex flex-col gap-4">
          <div>
            <h3 className="font-semibold text-slate-900">{t("failedTitle")}</h3>
            <p className="mt-1 text-rose-700">
              {isKnownFailure(step.payment.failureReason)
                ? tFailure(step.payment.failureReason)
                : tFailure("unknown")}
            </p>
          </div>
          <p className="text-sm text-slate-600">{t("failedHint")}</p>
          {step.payment.attempts > 0 && (
            <p className="text-xs text-slate-500">{t("attemptsUsed", { count: step.payment.attempts })}</p>
          )}
          <Button size="lg" onClick={payWithAnotherCard} loading={retrying}>
            {t("payAgain")}
          </Button>
        </div>
      )}

      {step.kind === "cancelled" && (
        <div className="flex flex-col gap-3">
          <Alert tone="danger">{t("orderCancelled")}</Alert>
          <ButtonLink href={`/orders/${orderId}`} variant="secondary">
            {t("toOrder")}
          </ButtonLink>
        </div>
      )}

      {error && <Alert tone="danger">{error}</Alert>}
    </div>
  );
}
