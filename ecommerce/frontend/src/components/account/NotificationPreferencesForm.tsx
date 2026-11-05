"use client";

import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { useToast } from "@/components/toast";
import { Alert, Spinner } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import type { NotificationPreferences } from "@/lib/types";

/** The customer's notification settings; each switch is saved as soon as it changes. */
export function NotificationPreferencesForm() {
  const t = useTranslations("notifications");
  const toast = useToast();
  const errorMessage = useErrorMessage();
  const [preferences, setPreferences] = useState<NotificationPreferences | null>(null);
  const [failed, setFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .get<NotificationPreferences>("/notifications/preferences")
      .then((result) => !cancelled && setPreferences(result))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, []);

  const change = async (reviewReminders: boolean) => {
    const previous = preferences;
    setPreferences({ reviewReminders });
    setSaving(true);
    try {
      setPreferences(await api.put<NotificationPreferences>("/notifications/preferences", { reviewReminders }));
      toast.success(t("preferencesSaved"));
    } catch (caught) {
      setPreferences(previous);
      toast.error(errorMessage(caught));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="card mb-6 p-5" aria-labelledby="notification-preferences-title">
      <h2 id="notification-preferences-title" className="mb-3 font-semibold text-slate-900">
        {t("preferencesTitle")}
      </h2>
      {failed ? (
        <Alert tone="warning">{t("preferencesLoadError")}</Alert>
      ) : preferences === null ? (
        <p className="flex items-center gap-2 text-sm text-slate-500" role="status">
          <Spinner />
        </p>
      ) : (
        <label className="flex cursor-pointer items-start gap-3">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 rounded border-slate-300 text-brand-600"
            checked={preferences.reviewReminders}
            disabled={saving}
            onChange={(event) => void change(event.target.checked)}
            aria-describedby="review-reminders-hint"
          />
          <span>
            <span className="block text-sm font-medium text-slate-900">{t("reviewReminders")}</span>
            <span id="review-reminders-hint" className="block text-xs text-slate-500">
              {t("reviewRemindersHint")}
            </span>
          </span>
        </label>
      )}
    </section>
  );
}
