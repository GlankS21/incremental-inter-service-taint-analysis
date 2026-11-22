"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useToast } from "@/components/toast";
import { Alert, Button } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";

/** Revokes every refresh token of the account (other devices, and then this browser). */
export function LogoutAllButton() {
  const t = useTranslations("sessions");
  const errorMessage = useErrorMessage();
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const logoutAll = async () => {
    if (!window.confirm(t("confirm"))) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.post("/auth/logout-all");
      // Access tokens stay valid until they expire: the cookies of this browser are cleared now
      await fetch("/api/auth/logout", { method: "POST" }).catch(() => undefined);
      toast.success(t("done"));
      router.replace("/login?next=/account");
      router.refresh();
    } catch (caught) {
      setBusy(false);
      setError(caught);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      {error !== null && <Alert tone="danger">{errorMessage(error)}</Alert>}
      <div>
        <Button variant="danger" loading={busy} onClick={logoutAll}>
          {t("logoutAll")}
        </Button>
      </div>
    </div>
  );
}
