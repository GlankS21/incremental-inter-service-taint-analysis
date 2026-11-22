"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { useState, type FormEvent } from "react";
import { Alert, Button, Field } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { ApiError } from "@/lib/errors";

/**
 * auth-service answers the same way whether the address is registered or not, so the confirmation
 * never reveals which e-mails have an account.
 */
export function ForgotPasswordForm() {
  const t = useTranslations("auth");
  const errorMessage = useErrorMessage();
  const [submitting, setSubmitting] = useState(false);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const email = String(new FormData(event.currentTarget).get("email") ?? "").trim();
    setSubmitting(true);
    setError(null);
    try {
      await api.post("/auth/password/forgot", { email });
      setSentTo(email);
    } catch (caught) {
      setError(caught);
    } finally {
      setSubmitting(false);
    }
  };

  if (sentTo) {
    return (
      <div className="flex flex-col gap-4">
        <Alert tone="success">{t("forgotSent", { email: sentTo })}</Alert>
        <p className="text-sm text-slate-500">{t("forgotSentHint")}</p>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Link href="/login" className="link text-sm">
            {t("backToLogin")}
          </Link>
          <Button variant="secondary" size="sm" onClick={() => setSentTo(null)}>
            {t("sendAgain")}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      {error !== null && <Alert tone="danger">{errorMessage(error)}</Alert>}
      <Field
        label={t("email")}
        htmlFor="email"
        error={error instanceof ApiError ? error.fieldError("email") : undefined}
        required
      >
        <input id="email" name="email" type="email" required maxLength={255} autoComplete="email" className="input" />
      </Field>
      <Button type="submit" size="lg" loading={submitting}>
        {t("sendLink")}
      </Button>
      <Link href="/login" className="link text-center text-sm">
        {t("backToLogin")}
      </Link>
    </form>
  );
}
