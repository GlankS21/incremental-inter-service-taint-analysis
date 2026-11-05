"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { RatingInput } from "@/components/product/RatingInput";
import { RatingStars } from "@/components/product/RatingStars";
import { VerifiedPurchase } from "@/components/product/VerifiedPurchase";
import { usePendingReviews } from "@/components/providers";
import { ReviewModal } from "@/components/review/ReviewModal";
import { useToast } from "@/components/toast";
import { Alert, Button, ButtonLink, EmptyState, Spinner, Thumb } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { formatDate } from "@/lib/format";
import { ACCOUNT_REVIEWS_PAGE_SIZE, ACCOUNT_REVIEW_TABS, type AccountReviewTab } from "@/lib/reviews";
import type { PageResponse, PendingReview, UserReview } from "@/lib/types";

type List<T> = { kind: "loading" } | { kind: "failed" } | { kind: "ready"; page: PageResponse<T>; items: T[] };

interface Dialog {
  productId: number;
  initialRating?: number;
}

/**
 * The two tabs of "Мои отзывы". Choosing stars on a product waiting for a review opens the review
 * dialog with them; once saved, the product moves to "Мои отзывы".
 */
export function AccountReviews({ initialTab, productId }: { initialTab: AccountReviewTab; productId: number | null }) {
  const t = useTranslations("myReviews");
  const tReviews = useTranslations("reviews");
  const tCommon = useTranslations("common");
  const router = useRouter();
  const toast = useToast();
  const errorMessage = useErrorMessage();
  const pendingReviews = usePendingReviews();
  const [tab, setTab] = useState<AccountReviewTab>(initialTab);
  const [pending, setPending] = useState<List<PendingReview>>({ kind: "loading" });
  const [mine, setMine] = useState<List<UserReview>>({ kind: "loading" });
  const [more, setMore] = useState<AccountReviewTab | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(productId ? { productId } : null);

  const loadPending = useCallback(async (page: number) => {
    try {
      const result = await api.get<PageResponse<PendingReview>>(`/reviews/pending?page=${page}&size=${ACCOUNT_REVIEWS_PAGE_SIZE}`);
      setPending((current) => ({
        kind: "ready",
        page: result,
        items: page > 0 && current.kind === "ready" ? [...current.items, ...result.content] : result.content,
      }));
    } catch {
      setPending((current) => (page > 0 && current.kind === "ready" ? current : { kind: "failed" }));
      if (page > 0) {
        toast.error(t("loadError"));
      }
    }
  }, [t, toast]);

  const loadMine = useCallback(async (page: number) => {
    try {
      const result = await api.get<PageResponse<UserReview>>(`/reviews/mine?page=${page}&size=${ACCOUNT_REVIEWS_PAGE_SIZE}`);
      setMine((current) => ({
        kind: "ready",
        page: result,
        items: page > 0 && current.kind === "ready" ? [...current.items, ...result.content] : result.content,
      }));
    } catch {
      setMine((current) => (page > 0 && current.kind === "ready" ? current : { kind: "failed" }));
      if (page > 0) {
        toast.error(t("loadError"));
      }
    }
  }, [t, toast]);

  const reload = useCallback(() => {
    void loadPending(0);
    void loadMine(0);
    void pendingReviews.refresh();
  }, [loadPending, loadMine, pendingReviews]);

  useEffect(() => {
    // Both tabs are loaded at once: the first one shows how many products wait for a review
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadPending(0);
    void loadMine(0);
  }, [loadPending, loadMine]);

  const showMore = async (which: AccountReviewTab) => {
    setMore(which);
    if (which === "pending" && pending.kind === "ready") {
      await loadPending(pending.page.page + 1);
    } else if (which === "mine" && mine.kind === "ready") {
      await loadMine(mine.page.page + 1);
    }
    setMore(null);
  };

  const closeDialog = () => {
    setDialog(null);
    if (productId) {
      // A link to one product (?product=) is done once its dialog is closed
      router.replace(tab === "pending" ? "/account/reviews" : `/account/reviews?tab=${tab}`, { scroll: false });
    }
  };

  const remove = async (review: UserReview) => {
    if (!window.confirm(tReviews("deleteConfirm"))) {
      return;
    }
    setDeleting(review.productId);
    try {
      await api.delete(`/reviews/products/${review.productId}`);
      toast.success(tReviews("deleted"));
      reload();
    } catch (caught) {
      toast.error(errorMessage(caught));
    } finally {
      setDeleting(null);
    }
  };

  const nameOf = (id: number) =>
    (pending.kind === "ready" ? pending.items.find((item) => item.productId === id)?.name : undefined) ??
    (mine.kind === "ready" ? mine.items.find((item) => item.productId === id)?.productName : undefined) ??
    null;

  return (
    <>
      <ReviewTabs
        active={tab}
        pendingCount={pending.kind === "ready" ? pending.page.totalElements : null}
        onSelect={setTab}
      />

      <div role="tabpanel" id={`reviews-panel-${tab}`} aria-labelledby={`reviews-tab-${tab}`} className="mt-5">
        {tab === "pending" ? (
          <ListState
            list={pending}
            onRetry={() => void loadPending(0)}
            empty={
              <EmptyState title={t("pendingEmpty")}>
                <p className="mb-4 text-sm text-slate-500">{t("pendingEmptyHint")}</p>
                <ButtonLink href="/products" variant="secondary">
                  {t("toCatalog")}
                </ButtonLink>
              </EmptyState>
            }
          >
            {(items) => (
              <ul className="flex flex-col gap-3">
                {items.map((item) => (
                  <li key={item.productId} className="card flex flex-col gap-4 p-4 sm:flex-row sm:items-center">
                    <div className="flex min-w-0 flex-1 items-center gap-4">
                      <Thumb src={item.imageUrl} alt={item.name} />
                      <div className="min-w-0">
                        <Link href={`/products/${item.slug}`} className="font-medium break-words text-slate-900 hover:text-brand-600">
                          {item.name}
                        </Link>
                        <p className="mt-1 text-xs text-slate-500">
                          {t("deliveredOn", { date: formatDate(item.deliveredAt) })} ·{" "}
                          <Link href={`/orders/${item.orderId}`} className="link">
                            {t("toOrder")}
                          </Link>
                        </p>
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                      <RatingInput
                        size="md"
                        value={0}
                        label={tReviews("rateProduct", { name: item.name })}
                        onChange={(rating) => setDialog({ productId: item.productId, initialRating: rating })}
                      />
                      <Button size="sm" onClick={() => setDialog({ productId: item.productId })}>
                        {tReviews("writeReview")}
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </ListState>
        ) : (
          <ListState
            list={mine}
            onRetry={() => void loadMine(0)}
            empty={
              <EmptyState title={t("mineEmpty")}>
                <p className="mb-4 text-sm text-slate-500">{t("mineEmptyHint")}</p>
                <Button variant="secondary" onClick={() => setTab("pending")}>
                  {t("tabPendingLoading")}
                </Button>
              </EmptyState>
            }
          >
            {(items) => (
              <ul className="flex flex-col gap-3">
                {items.map((review) => (
                  <li key={review.id} className="card flex flex-col gap-3 p-4 sm:flex-row">
                    <Thumb src={review.productImageUrl} alt={review.productName} />
                    <div className="min-w-0 flex-1">
                      {review.status === "PUBLISHED" ? (
                        <Link href={`/products/${review.productSlug}`} className="font-medium break-words text-slate-900 hover:text-brand-600">
                          {review.productName}
                        </Link>
                      ) : (
                        <p className="font-medium break-words text-slate-900">{review.productName}</p>
                      )}
                      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
                        <RatingStars value={review.rating} size="sm" />
                        {review.verifiedPurchase && <VerifiedPurchase />}
                        <span className="text-xs text-slate-500">
                          {t("writtenOn", { date: formatDate(review.createdAt) })}
                          {review.updatedAt !== review.createdAt && ` · ${tReviews("edited", { date: formatDate(review.updatedAt) })}`}
                        </span>
                      </div>
                      {review.comment && (
                        <p className="mt-2 text-sm leading-relaxed break-words whitespace-pre-line text-slate-700">{review.comment}</p>
                      )}
                      {review.status === "HIDDEN" && <p className="mt-2 text-xs text-amber-700">{t("hidden")}</p>}
                    </div>
                    <div className="flex shrink-0 gap-2 sm:flex-col">
                      {review.status === "PUBLISHED" && (
                        <Button size="sm" variant="secondary" onClick={() => setDialog({ productId: review.productId })}>
                          {tReviews("editReview")}
                        </Button>
                      )}
                      <Button
                        size="sm"
                        variant="ghost"
                        loading={deleting === review.productId}
                        disabled={deleting !== null}
                        onClick={() => void remove(review)}
                      >
                        {tReviews("delete")}
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </ListState>
        )}

        {(tab === "pending" ? pending : mine).kind === "ready" && !lastPage(tab === "pending" ? pending : mine) && (
          <div className="mt-4">
            <Button variant="secondary" size="sm" loading={more === tab} onClick={() => void showMore(tab)}>
              {tCommon("showMore")}
            </Button>
          </div>
        )}
      </div>

      {dialog && (
        <ReviewModal
          productId={dialog.productId}
          productName={nameOf(dialog.productId)}
          initialRating={dialog.initialRating}
          onClose={closeDialog}
          onSaved={(review) => {
            if (review) {
              // Saved from "Ожидают отзыва": the product now belongs to the other tab
              setPending((current) =>
                current.kind === "ready"
                  ? {
                      ...current,
                      items: current.items.filter((item) => item.productId !== review.productId),
                      page: { ...current.page, totalElements: Math.max(0, current.page.totalElements - (current.items.some((item) => item.productId === review.productId) ? 1 : 0)) },
                    }
                  : current,
              );
            }
            reload();
          }}
        />
      )}
    </>
  );
}

function lastPage(list: List<unknown>): boolean {
  return list.kind !== "ready" || list.page.last;
}

/**
 * The tab list of "Мои отзывы": a button per tab, switched with the mouse or the arrow keys.
 *
 * @param pendingCount shown in the first tab; null while unknown
 */
export function ReviewTabs({
  active,
  pendingCount,
  onSelect,
}: {
  active: AccountReviewTab;
  pendingCount: number | null;
  onSelect: (tab: AccountReviewTab) => void;
}) {
  const t = useTranslations("myReviews");
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const labels: Record<AccountReviewTab, string> = {
    pending: pendingCount === null ? t("tabPendingLoading") : t("tabPending", { count: pendingCount }),
    mine: t("tabMine"),
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") {
      return;
    }
    event.preventDefault();
    const index = ACCOUNT_REVIEW_TABS.indexOf(active);
    const next = (index + (event.key === "ArrowRight" ? 1 : -1) + ACCOUNT_REVIEW_TABS.length) % ACCOUNT_REVIEW_TABS.length;
    onSelect(ACCOUNT_REVIEW_TABS[next]);
    tabs.current[next]?.focus();
  };

  return (
    <div role="tablist" aria-label={t("tabsLabel")} className="flex gap-2 overflow-x-auto border-b border-slate-200" onKeyDown={onKeyDown}>
      {ACCOUNT_REVIEW_TABS.map((tab, index) => (
        <button
          key={tab}
          ref={(element) => {
            tabs.current[index] = element;
          }}
          type="button"
          role="tab"
          id={`reviews-tab-${tab}`}
          aria-selected={active === tab}
          aria-controls={`reviews-panel-${tab}`}
          tabIndex={active === tab ? 0 : -1}
          onClick={() => onSelect(tab)}
          className={`-mb-px border-b-2 px-3 py-2 text-sm whitespace-nowrap ${
            active === tab ? "border-brand-600 font-medium text-brand-700" : "border-transparent text-slate-600 hover:text-slate-900"
          }`}
        >
          {labels[tab]}
        </button>
      ))}
    </div>
  );
}

/** Loading, error (with a retry), empty and filled states of one tab. */
function ListState<T>({
  list,
  onRetry,
  empty,
  children,
}: {
  list: List<T>;
  onRetry: () => void;
  empty: React.ReactNode;
  children: (items: T[]) => React.ReactNode;
}) {
  const t = useTranslations("myReviews");
  const tCommon = useTranslations("common");
  if (list.kind === "loading") {
    return (
      <p role="status" className="flex items-center gap-2 py-6 text-sm text-slate-500">
        <Spinner /> {tCommon("loading")}
      </p>
    );
  }
  if (list.kind === "failed") {
    return (
      <div className="flex flex-col items-start gap-3">
        <Alert tone="danger">{t("loadError")}</Alert>
        <Button variant="secondary" size="sm" onClick={onRetry}>
          {t("retry")}
        </Button>
      </div>
    );
  }
  return list.items.length === 0 ? empty : children(list.items);
}
