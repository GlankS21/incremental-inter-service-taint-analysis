"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useToast } from "@/components/toast";
import { Alert, Button, Field } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { ApiError } from "@/lib/errors";

/**
 * Sets a new password with the single-use token of the e-mailed link. auth-service ends every
 * session of the account, so the user signs in again afterwards.
 */
export function ResetPasswordForm({ token }: { token: string }) {
  const t = useTranslations("auth");
  const errorMessage = useErrorMessage();
  const toast = useToast();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const newPassword = String(form.get("newPassword") ?? "");
    if (newPassword !== String(form.get("confirmPassword") ?? "")) {
      setError(t("passwordMismatch"));
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.post("/auth/password/reset", { token, newPassword });
      // A session of this browser, if any, was revoked with the others
      await fetch("/api/auth/logout", { method: "POST" }).catch(() => undefined);
      toast.success(t("resetDone"));
      router.replace("/login");
      router.refresh();
    } catch (caught) {
      setSubmitting(false);
      setError(caught);
    }
  };

  // An invalid or expired link is a 400 without field violations, explained by auth-service
  const tokenRejected = error instanceof ApiError && error.status === 400 && error.violations.length === 0;
  const message =
    typeof error === "string" ? error : tokenRejected ? (error as ApiError).message : error !== null ? errorMessage(error) : null;

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      {message && (
        <Alert tone="danger">
          {message}
          {tokenRejected && (
            <Link href="/forgot-password" className="link mt-1 block">
              {t("requestNewLink")}
            </Link>
          )}
        </Alert>
      )}
      <Field
        label={t("newPassword")}
        htmlFor="newPassword"
        hint={t("passwordHint")}
        error={error instanceof ApiError ? error.fieldError("newPassword") : undefined}
        required
      >
        <input
          id="newPassword"
          name="newPassword"
          type="password"
          required
          minLength={8}
          maxLength={72}
          pattern="(?=.*[A-Za-z])(?=.*\d).{8,72}"
          title={t("passwordHint")}
          autoComplete="new-password"
          className="input"
        />
      </Field>
      <Field label={t("confirmPassword")} htmlFor="confirmPassword" required>
        <input id="confirmPassword" name="confirmPassword" type="password" required maxLength={72} autoComplete="new-password" className="input" />
      </Field>
      <Button type="submit" size="lg" loading={submitting}>
        {t("resetSubmit")}
      </Button>
    </form>
  );
}
