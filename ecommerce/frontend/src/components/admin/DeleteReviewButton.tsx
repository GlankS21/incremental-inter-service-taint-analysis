"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useToast } from "@/components/toast";
import { Button } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import type { AdminReview } from "@/lib/types";

/** Removes an inappropriate review; product-service recomputes the product's rating. */
export function DeleteReviewButton({ review }: { review: AdminReview }) {
  const t = useTranslations("adminReviews");
  const tCommon = useTranslations("common");
  const errorMessage = useErrorMessage();
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState(false);

  const remove = async () => {
    if (!window.confirm(t("deleteConfirm", { author: review.authorName, product: review.productName }))) {
      return;
    }
    setBusy(true);
    try {
      await api.delete(`/reviews/admin/${review.id}`);
      toast.success(t("deleted"));
      router.refresh();
    } catch (caught) {
      toast.error(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Button variant="ghost" size="sm" loading={busy} onClick={() => void remove()}>
      {tCommon("delete")}
    </Button>
  );
}
