"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { useCallback, useEffect, useRef, useState } from "react";
import { useSession } from "@/components/providers";
import { ReviewForm } from "@/components/product/ReviewForm";
import { Button, Spinner } from "@/components/ui";
import { useMyReview } from "@/lib/client/use-my-review";
import { formatDate } from "@/lib/format";

/** Anchor of the review form: /products/<slug>#review opens it (links of e-mails and notifications). */
const REVIEW_ANCHOR = "review";

/**
 * The signed-in customer's review of the product. product-service only accepts reviews from
 * customers who received the product, and tells whether this one did; a buyer who has not reviewed it
 * yet is invited to.
 */
export function ReviewEditor({ productId, productSlug, onChanged }: { productId: number; productSlug: string; onChanged: () => void }) {
  const t = useTranslations("reviews");
  const session = useSession();
  const { state, setReview } = useMyReview(productId, Boolean(session));
  const [open, setOpen] = useState(false);
  const anchorRef = useRef<HTMLDivElement>(null);

  const openForm = useCallback(() => {
    setOpen(true);
    anchorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, []);

  useEffect(() => {
    // #review opens the form once we know the customer may write one
    const openFromHash = () => {
      if (window.location.hash === `#${REVIEW_ANCHOR}` && state.kind === "ready" && state.mine.canReview) {
        openForm();
      }
    };
    openFromHash();
    window.addEventListener("hashchange", openFromHash);
    return () => window.removeEventListener("hashchange", openFromHash);
  }, [state, openForm]);

  const content = () => {
    if (!session) {
      return (
        <Link href={`/login?next=${encodeURIComponent(`/products/${productSlug}#${REVIEW_ANCHOR}`)}`} className="link text-sm">
          {t("signInToReview")}
        </Link>
      );
    }
    if (state.kind === "idle" || state.kind === "loading") {
      return (
        <p className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner /> {t("checking")}
        </p>
      );
    }
    if (state.kind === "failed") {
      return null;
    }
    if (!state.mine.canReview) {
      return <p className="text-sm text-slate-500">{t("onlyBuyers")}</p>;
    }
    if (open) {
      return (
        <ReviewForm
          productId={productId}
          review={state.mine.review}
          defaultName={state.defaultName}
          onCancel={() => setOpen(false)}
          onSaved={(review) => {
            setReview(review);
            setOpen(false);
            onChanged();
          }}
        />
      );
    }
    if (!state.mine.review && state.mine.purchasedAt) {
      return (
        <div className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm font-medium text-amber-900">{t("purchasedPrompt", { date: formatDate(state.mine.purchasedAt) })}</p>
          <Button onClick={openForm} className="shrink-0">
            {t("purchasedPromptAction")}
          </Button>
        </div>
      );
    }
    return (
      <Button variant={state.mine.review ? "secondary" : "primary"} onClick={openForm}>
        {state.mine.review ? t("editReview") : t("writeReview")}
      </Button>
    );
  };

  return (
    <div id={REVIEW_ANCHOR} ref={anchorRef} className="scroll-mt-32">
      {content()}
    </div>
  );
}
