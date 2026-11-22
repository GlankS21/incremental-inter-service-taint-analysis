"use client";

import { useTranslations } from "next-intl";
import { Button, EmptyState } from "@/components/ui";

/** Errors of an admin page, rendered inside the admin layout: the navigation stays usable. */
export default function AdminErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useTranslations("errorPage");
  return (
    <EmptyState title={t("title")}>
      <p className="max-w-md text-sm text-slate-500">
        {t("text")}
        {error.digest && <span className="mt-2 block text-xs text-slate-400">{t("code", { digest: error.digest })}</span>}
      </p>
      <Button onClick={reset}>{t("retry")}</Button>
    </EmptyState>
  );
}
