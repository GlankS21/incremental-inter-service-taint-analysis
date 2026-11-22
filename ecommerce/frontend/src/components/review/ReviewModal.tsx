"use client";

import { useTranslations } from "next-intl";
import { Modal } from "@/components/Modal";
import { ReviewForm } from "@/components/product/ReviewForm";
import { Alert, Spinner } from "@/components/ui";
import { useMyReview } from "@/lib/client/use-my-review";
import type { Review } from "@/lib/types";

interface Props {
  productId: number;
  /** Shown in the title; a generic title is used while unknown. */
  productName: string | null;
  /** Stars already chosen on the product row, for a product not reviewed yet. */
  initialRating?: number;
  onClose: () => void;
  /** Called with the saved review, or null once deleted; the dialog closes itself. */
  onSaved: (review: Review | null) => void;
}

/**
 * Writes or edits the review of a product without leaving the page (order detail, "Мои отзывы").
 * Mounted while open: the customer's current review is loaded each time it opens.
 */
export function ReviewModal({ productId, productName, initialRating, onClose, onSaved }: Props) {
  const t = useTranslations("reviews");
  const { state } = useMyReview(productId, true);
  const title = productName ? t("modalTitle", { name: productName }) : t("yourReview");

  return (
    <Modal open onClose={onClose} title={title}>
      {state.kind === "ready" ? (
        state.mine.canReview ? (
          <ReviewForm
            bare
            productId={productId}
            review={state.mine.review}
            defaultName={state.defaultName}
            initialRating={initialRating}
            onCancel={onClose}
            onSaved={(review) => {
              onSaved(review);
              onClose();
            }}
          />
        ) : (
          <Alert tone="info">{state.mine.purchased ? t("notReviewable") : t("onlyBuyers")}</Alert>
        )
      ) : state.kind === "failed" ? (
        <Alert tone="danger">{t("loadError")}</Alert>
      ) : (
        <p className="flex items-center gap-2 text-sm text-slate-500" role="status">
          <Spinner /> {t("checking")}
        </p>
      )}
    </Modal>
  );
}
