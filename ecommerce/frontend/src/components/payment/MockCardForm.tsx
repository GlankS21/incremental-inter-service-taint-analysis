"use client";

import { useTranslations } from "next-intl";
import { useState, type FormEvent } from "react";
import { Alert, Button, Field } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { ApiError } from "@/lib/errors";
import { formatMoney } from "@/lib/format";
import type { MockCardPaymentResult, Payment } from "@/lib/types";

/** A few YooKassa test cards, as a reminder; the backend knows the whole table. */
const TEST_CARDS = [
  { number: "5555 5555 5555 4444", outcome: "success" },
  { number: "5555 5555 5555 4477", outcome: "success3ds" },
  { number: "5555 5555 5555 4600", outcome: "insufficient_funds" },
  { number: "5555 5555 5555 4527", outcome: "general_decline" },
  { number: "5555 5555 5555 4543", outcome: "card_expired" },
  { number: "5555 5555 5555 4626", outcome: "invalid_csc" },
  { number: "5555 5555 5555 4592", outcome: "3d_secure_failed" },
] as const;

/** "5555555555554444" → "5555 5555 5555 4444" */
function groupDigits(value: string): string {
  return value
    .replace(/\D/g, "")
    .slice(0, 19)
    .replace(/(\d{4})(?=\d)/g, "$1 ");
}

/** "1230" → "12/30" */
function formatExpiry(value: string): string {
  const digits = value.replace(/\D/g, "").slice(0, 4);
  return digits.length > 2 ? `${digits.slice(0, 2)}/${digits.slice(2)}` : digits;
}

interface Props {
  payment: Payment;
  /** Provider id of the pending mock payment. */
  paymentId: string;
  onResult: (payment: Payment) => void;
}

/**
 * Stand-in for the YooKassa widget when PAYMENT_PROVIDER=mock: the outcome follows the table of
 * YooKassa test cards, with an imitated 3-D Secure step for the cards that ask for it.
 */
export function MockCardForm({ payment, paymentId, onResult }: Props) {
  const t = useTranslations("mockCard");
  const errorMessage = useErrorMessage();
  const [card, setCard] = useState("");
  const [expiry, setExpiry] = useState("");
  const [cvc, setCvc] = useState("");
  const [threeDSecure, setThreeDSecure] = useState(false);
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | string | null>(null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const result = await api.post<MockCardPaymentResult>(`/payments/mock/${encodeURIComponent(paymentId)}/pay`, {
        cardNumber: card.replace(/\s/g, ""),
        expiry,
        cvc,
        threeDSecureCode: threeDSecure ? code : null,
      });
      if (result.threeDSecureRequired) {
        setThreeDSecure(true);
        setSubmitting(false);
        return;
      }
      onResult(result.payment);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : errorMessage(caught));
      setSubmitting(false);
    }
  };

  const invalidFields = error instanceof ApiError && error.violations.length > 0;
  const message = typeof error === "string" ? error : error ? errorMessage(error) : null;

  if (threeDSecure) {
    return (
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div>
          <h3 className="font-semibold text-slate-900">{t("threeDSecureTitle")}</h3>
          <p className="mt-1 text-sm text-slate-600">
            {t("threeDSecureText", { amount: formatMoney(payment.amount, payment.currency) })}
          </p>
        </div>
        <Field label={t("threeDSecureCode")} htmlFor="mock-3ds" hint={t("threeDSecureHint")}>
          <input
            id="mock-3ds"
            className="input"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={8}
            required
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
          />
        </Field>
        {message && <Alert tone="danger">{message}</Alert>}
        <Button type="submit" loading={submitting}>
          {t("confirm")}
        </Button>
      </form>
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Alert tone="info">{t("notice")}</Alert>
      <Field label={t("cardNumber")} htmlFor="mock-card" required>
        <input
          id="mock-card"
          className="input font-mono"
          inputMode="numeric"
          autoComplete="cc-number"
          placeholder="0000 0000 0000 0000"
          required
          value={card}
          onChange={(event) => setCard(groupDigits(event.target.value))}
        />
      </Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label={t("expiry")} htmlFor="mock-expiry" required>
          <input
            id="mock-expiry"
            className="input font-mono"
            inputMode="numeric"
            autoComplete="cc-exp"
            placeholder={t("expiryPlaceholder")}
            required
            pattern="(0[1-9]|1[0-2])/\d{2}"
            value={expiry}
            onChange={(event) => setExpiry(formatExpiry(event.target.value))}
          />
        </Field>
        <Field label={t("cvc")} htmlFor="mock-cvc" required>
          <input
            id="mock-cvc"
            className="input font-mono"
            inputMode="numeric"
            autoComplete="cc-csc"
            type="password"
            placeholder="123"
            required
            pattern="\d{3,4}"
            maxLength={4}
            value={cvc}
            onChange={(event) => setCvc(event.target.value.replace(/\D/g, ""))}
          />
        </Field>
      </div>
      {invalidFields && <Alert tone="danger">{t("invalidCard")}</Alert>}
      {message && !invalidFields && <Alert tone="danger">{message}</Alert>}
      <Button type="submit" size="lg" loading={submitting}>
        {t("pay", { amount: formatMoney(payment.amount, payment.currency) })}
      </Button>
      <details className="rounded-lg border border-slate-200 p-3 text-sm">
        <summary className="cursor-pointer font-medium text-slate-700">{t("testCards")}</summary>
        <p className="mt-2 text-xs text-slate-500">{t("testCardsHint")}</p>
        <ul className="mt-2 flex flex-col gap-1">
          {TEST_CARDS.map((testCard) => (
            <li key={testCard.number} className="flex flex-wrap justify-between gap-2">
              <button
                type="button"
                className="font-mono text-brand-700 hover:underline"
                onClick={() => setCard(testCard.number)}
              >
                {testCard.number}
              </button>
              <span className="text-slate-600">{t(`outcome.${testCard.outcome}`)}</span>
            </li>
          ))}
        </ul>
      </details>
    </form>
  );
}
