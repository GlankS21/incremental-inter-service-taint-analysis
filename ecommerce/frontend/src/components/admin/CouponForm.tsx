"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useToast } from "@/components/toast";
import { Alert, Button, Field } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { fromDateTimeLocal, toDateTimeLocal } from "@/lib/coupons";
import { ApiError } from "@/lib/errors";
import type { Coupon, CouponDiscountType, CouponRequest } from "@/lib/types";

const TYPES: CouponDiscountType[] = ["PERCENTAGE", "FIXED_AMOUNT"];

/** Creates a coupon, or edits one (with deletion while it has never been used). */
export function CouponForm({ coupon }: { coupon?: Coupon }) {
  const t = useTranslations("adminCoupons");
  const tCommon = useTranslations("common");
  const errorMessage = useErrorMessage();
  const toast = useToast();
  const router = useRouter();
  const [type, setType] = useState<CouponDiscountType>(coupon?.discountType ?? "PERCENTAGE");
  const [busy, setBusy] = useState<"save" | "delete" | null>(null);
  const [error, setError] = useState<unknown>(null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const number = (name: string) => (text(name) ? Number(text(name)) : null);
    const body: CouponRequest = {
      code: text("code").toUpperCase(),
      description: text("description") || null,
      discountType: type,
      discountValue: Number(text("discountValue")),
      minOrderAmount: number("minOrderAmount"),
      maxDiscountAmount: type === "PERCENTAGE" ? number("maxDiscountAmount") : null,
      startsAt: fromDateTimeLocal(text("startsAt")),
      expiresAt: fromDateTimeLocal(text("expiresAt")),
      usageLimit: number("usageLimit"),
      perUserLimit: number("perUserLimit"),
      active: form.get("active") === "on",
    };
    setBusy("save");
    setError(null);
    try {
      if (coupon) {
        await api.put<Coupon>(`/orders/admin/coupons/${coupon.id}`, body);
        toast.success(t("saved"));
        setBusy(null);
        router.refresh();
      } else {
        const created = await api.post<Coupon>("/orders/admin/coupons", body);
        toast.success(t("created", { code: created.code }));
        // Stays busy until the coupon's page opens: a second click would create a second coupon
        router.push(`/admin/coupons/${created.id}`);
      }
    } catch (caught) {
      setBusy(null);
      setError(caught);
    }
  };

  const remove = async () => {
    if (!coupon || !window.confirm(t("deleteConfirm", { code: coupon.code }))) {
      return;
    }
    setBusy("delete");
    setError(null);
    try {
      await api.delete(`/orders/admin/coupons/${coupon.id}`);
      toast.success(t("deleted"));
      router.push("/admin/coupons");
      router.refresh();
    } catch (caught) {
      setBusy(null);
      setError(caught);
    }
  };

  const fieldError = (name: string) => (error instanceof ApiError ? error.fieldError(name) : undefined);
  // Cross-field rules (period, percentage range) are 400 answers without field violations
  const message =
    error instanceof ApiError && error.status === 400 && error.violations.length === 0
      ? error.message
      : error !== null
        ? errorMessage(error)
        : null;

  return (
    <form onSubmit={submit} className="flex flex-col gap-6">
      <section className="card grid gap-4 p-6 sm:grid-cols-2">
        <Field label={t("code")} htmlFor="code" hint={t("codeHint")} error={fieldError("code")} required>
          <input
            id="code"
            name="code"
            required
            minLength={3}
            maxLength={30}
            pattern="[A-Za-z0-9_\-]{3,30}"
            defaultValue={coupon?.code}
            autoComplete="off"
            className="input uppercase"
          />
        </Field>
        <Field label={t("discountType")} htmlFor="discountType" required>
          <select
            id="discountType"
            value={type}
            onChange={(event) => setType(event.target.value as CouponDiscountType)}
            className="input"
          >
            {TYPES.map((value) => (
              <option key={value} value={value}>
                {t(value)}
              </option>
            ))}
          </select>
        </Field>
        <Field label={t("description")} htmlFor="description" hint={t("descriptionHint")} error={fieldError("description")} className="sm:col-span-2">
          <input id="description" name="description" maxLength={255} defaultValue={coupon?.description ?? ""} className="input" />
        </Field>
        <Field
          label={type === "PERCENTAGE" ? t("discountValuePercent") : t("discountValueAmount")}
          htmlFor="discountValue"
          error={fieldError("discountValue")}
          required
        >
          <input
            id="discountValue"
            name="discountValue"
            type="number"
            required
            min={0.01}
            max={type === "PERCENTAGE" ? 100 : undefined}
            step="0.01"
            defaultValue={coupon?.discountValue}
            className="input"
          />
        </Field>
        <Field label={t("maxDiscountAmount")} htmlFor="maxDiscountAmount" hint={t("maxDiscountHint")} error={fieldError("maxDiscountAmount")}>
          <input
            id="maxDiscountAmount"
            name="maxDiscountAmount"
            type="number"
            min={0.01}
            step="0.01"
            disabled={type !== "PERCENTAGE"}
            defaultValue={coupon?.maxDiscountAmount ?? ""}
            className="input"
          />
        </Field>
        <Field label={t("minOrderAmount")} htmlFor="minOrderAmount" error={fieldError("minOrderAmount")}>
          <input
            id="minOrderAmount"
            name="minOrderAmount"
            type="number"
            min={0.01}
            step="0.01"
            defaultValue={coupon?.minOrderAmount ?? ""}
            className="input"
          />
        </Field>
        <div className="hidden sm:block" />
        <Field label={t("startsAt")} htmlFor="startsAt" hint={t("periodHint")} error={fieldError("startsAt")}>
          <input id="startsAt" name="startsAt" type="datetime-local" defaultValue={toDateTimeLocal(coupon?.startsAt)} className="input" />
        </Field>
        <Field label={t("expiresAt")} htmlFor="expiresAt" hint={t("periodHint")} error={fieldError("expiresAt")}>
          <input id="expiresAt" name="expiresAt" type="datetime-local" defaultValue={toDateTimeLocal(coupon?.expiresAt)} className="input" />
        </Field>
        <Field label={t("usageLimit")} htmlFor="usageLimit" hint={t("limitHint")} error={fieldError("usageLimit")}>
          <input id="usageLimit" name="usageLimit" type="number" min={1} step={1} defaultValue={coupon?.usageLimit ?? ""} className="input" />
        </Field>
        <Field label={t("perUserLimit")} htmlFor="perUserLimit" hint={t("limitHint")} error={fieldError("perUserLimit")}>
          <input
            id="perUserLimit"
            name="perUserLimit"
            type="number"
            min={1}
            step={1}
            defaultValue={coupon?.perUserLimit ?? ""}
            className="input"
          />
        </Field>
        <label className="flex items-center gap-2 text-sm text-slate-700 sm:col-span-2">
          <input type="checkbox" name="active" defaultChecked={coupon?.active ?? true} />
          {t("activeLabel")}
        </label>
      </section>

      {message && <Alert tone="danger">{message}</Alert>}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" size="lg" loading={busy === "save"} disabled={busy !== null}>
          {coupon ? tCommon("save") : t("create")}
        </Button>
        {coupon &&
          (coupon.usedCount === 0 ? (
            <Button type="button" variant="danger" loading={busy === "delete"} disabled={busy !== null} onClick={() => void remove()}>
              {tCommon("delete")}
            </Button>
          ) : (
            <p className="text-xs text-slate-500">{t("inUseHint")}</p>
          ))}
      </div>
    </form>
  );
}
