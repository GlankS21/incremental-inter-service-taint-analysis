"use client";

import { useTranslations } from "next-intl";
import { Button, EmptyState } from "@/components/ui";

/**
 * Shown by the proxy instead of a protected page while auth-service cannot renew the session: the
 * session is kept, and reloading the page tries again.
 */
export default function UnavailablePage() {
  const t = useTranslations("errorPage");
  return (
    <div className="container-page py-16">
      <EmptyState title={t("title")}>
        <p className="max-w-md text-sm text-slate-500">{t("text")}</p>
        <Button onClick={() => window.location.reload()}>{t("retry")}</Button>
      </EmptyState>
    </div>
  );
}
