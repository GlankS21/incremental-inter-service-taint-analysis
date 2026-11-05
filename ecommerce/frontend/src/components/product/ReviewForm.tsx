"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useId, useState, type FormEvent } from "react";
import { usePendingReviews } from "@/components/providers";
import { RatingInput } from "@/components/product/RatingInput";
import { useToast } from "@/components/toast";
import { Alert, Button, Field } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { ApiError } from "@/lib/errors";
import type { Review } from "@/lib/types";

interface Props {
  productId: number;
  review: Review | null;
  defaultName: string;
  /** Stars already chosen (e.g. on a product row) when there is no review yet. */
  initialRating?: number;
  /** Called with the saved review, or null once deleted. */
  onSaved: (review: Review | null) => void;
  onCancel: () => void;
  /** Without the "Ваш отзыв" heading and the grey panel (inside a dialog that has its own title). */
  bare?: boolean;
}

/**
 * Creates, edits or deletes the customer's review of a product. Used on the product page and in the
 * review dialog of the order and "Мои отзывы" pages.
 */
export function ReviewForm({ productId, review, defaultName, initialRating, onSaved, onCancel, bare }: Props) {
  const t = useTranslations("reviews");
  const tCommon = useTranslations("common");
  const errorMessage = useErrorMessage();
  const toast = useToast();
  const router = useRouter();
  const pendingReviews = usePendingReviews();
  const ids = useId();
  const [rating, setRating] = useState(review?.rating ?? initialRating ?? 0);
  const [busy, setBusy] = useState<"save" | "delete" | null>(null);
  const [error, setError] = useState<unknown>(null);

  const finish = (saved: Review | null, message: string) => {
    toast.success(message);
    onSaved(saved);
    // The number of products waiting for a review and the ratings shown on server-rendered pages change
    void pendingReviews.refresh();
    router.refresh();
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (rating < 1) {
      setError(t("chooseRating"));
      return;
    }
    const form = new FormData(event.currentTarget);
    setBusy("save");
    setError(null);
    try {
      const saved = await api.put<Review>(`/reviews/products/${productId}`, {
        rating,
        comment: String(form.get("comment") ?? "").trim() || null,
        authorName: String(form.get("authorName") ?? "").trim(),
      });
      finish(saved, t("saved"));
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    if (!window.confirm(t("deleteConfirm"))) {
      return;
    }
    setBusy("delete");
    setError(null);
    try {
      await api.delete(`/reviews/products/${productId}`);
      setRating(0);
      finish(null, t("deleted"));
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(null);
    }
  };

  const fieldError = (name: string) => (error instanceof ApiError ? error.fieldError(name) : undefined);
  const message = typeof error === "string" ? error : error !== null ? errorMessage(error) : null;

  return (
    <form onSubmit={submit} className={bare ? "flex flex-col gap-4" : "flex max-w-xl flex-col gap-4 rounded-xl bg-slate-50 p-4 sm:p-5"}>
      {!bare && <h3 className="font-semibold text-slate-900">{t("yourReview")}</h3>}
      {message && <Alert tone="danger">{message}</Alert>}
      <div>
        <p id={`${ids}-rating`} className="label">
          {t("rating")}
          <span className="text-rose-600"> *</span>
        </p>
        <RatingInput value={rating} onChange={setRating} label={t("rating")} labelledBy={`${ids}-rating`} />
        {fieldError("rating") && <p className="mt-1 text-xs text-rose-600">{fieldError("rating")}</p>}
      </div>
      <Field label={t("comment")} htmlFor={`${ids}-comment`} error={fieldError("comment")}>
        <textarea
          id={`${ids}-comment`}
          name="comment"
          rows={4}
          maxLength={2000}
          defaultValue={review?.comment ?? ""}
          placeholder={t("commentPlaceholder")}
          className="input"
        />
      </Field>
      <Field label={t("authorName")} htmlFor={`${ids}-author`} error={fieldError("authorName")} required>
        <input
          id={`${ids}-author`}
          name="authorName"
          required
          maxLength={60}
          defaultValue={defaultName}
          autoComplete="given-name"
          className="input sm:max-w-xs"
        />
      </Field>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" loading={busy === "save"} disabled={busy !== null}>
          {review ? t("update") : t("submit")}
        </Button>
        <Button type="button" variant="ghost" disabled={busy !== null} onClick={onCancel}>
          {tCommon("cancel")}
        </Button>
        {review && (
          <Button type="button" variant="danger" className="sm:ml-auto" loading={busy === "delete"} disabled={busy !== null} onClick={() => void remove()}>
            {t("delete")}
          </Button>
        )}
      </div>
    </form>
  );
}
