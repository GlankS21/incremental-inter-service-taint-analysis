"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { useCart, useSession } from "@/components/providers";
import { Alert, Button } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";
import { clampQuantity, quantityDraft } from "@/lib/quantity";

const MAX_PER_LINE = 99;

/** @param available units that can be ordered, null when unknown */
export function AddToCart({ productId, available }: { productId: number; available: number | null }) {
  const t = useTranslations("addToCart");
  const errorMessage = useErrorMessage();
  const session = useSession();
  const { refresh } = useCart();
  const router = useRouter();
  const pathname = usePathname();
  // The typed text: the field can be emptied to type another number, it is clamped on blur and submit
  const [draft, setDraft] = useState("1");
  const [state, setState] = useState<{ kind: "idle" | "saving" } | { kind: "added" } | { kind: "error"; message: string }>({
    kind: "idle",
  });

  const max = Math.max(1, Math.min(MAX_PER_LINE, available ?? MAX_PER_LINE));
  const soldOut = available !== null && available <= 0;
  const quantity = clampQuantity(draft, max);
  const setQuantity = (value: number) => setDraft(String(value));

  const add = async (thenCheckout: boolean) => {
    if (!session) {
      router.push(`/login?next=${encodeURIComponent(pathname)}`);
      return;
    }
    setQuantity(quantity);
    setState({ kind: "saving" });
    try {
      await api.post("/cart/items", { productId, quantity });
      await refresh();
      if (thenCheckout) {
        router.push("/cart");
      } else {
        setState({ kind: "added" });
      }
    } catch (error) {
      setState({ kind: "error", message: errorMessage(error) });
    }
  };

  if (soldOut) {
    return <Alert tone="warning">{t("soldOut")}</Alert>;
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <div className="inline-flex items-center rounded-lg border border-slate-300 bg-white">
          <button
            type="button"
            className="px-3 py-2 text-lg text-slate-600 hover:bg-slate-50 disabled:text-slate-300"
            onClick={() => setQuantity(Math.max(1, quantity - 1))}
            disabled={quantity <= 1}
            aria-label={t("decrease")}
          >
            −
          </button>
          <input
            type="number"
            min={1}
            max={max}
            value={draft}
            onChange={(event) => setDraft(quantityDraft(event.target.value))}
            onBlur={() => setQuantity(quantity)}
            className="w-14 border-x border-slate-300 py-2 text-center text-sm focus:outline-none"
            aria-label={t("quantity")}
          />
          <button
            type="button"
            className="px-3 py-2 text-lg text-slate-600 hover:bg-slate-50 disabled:text-slate-300"
            onClick={() => setQuantity(Math.min(max, quantity + 1))}
            disabled={quantity >= max}
            aria-label={t("increase")}
          >
            +
          </button>
        </div>
        <Button size="lg" onClick={() => add(false)} loading={state.kind === "saving"}>
          {t("add")}
        </Button>
        <Button size="lg" variant="secondary" onClick={() => add(true)} disabled={state.kind === "saving"}>
          {t("buyNow")}
        </Button>
      </div>
      {state.kind === "added" && (
        <Alert tone="success">
          {t("added")}{" "}
          <Link href="/cart" className="font-medium underline">
            {t("viewCart")}
          </Link>
        </Alert>
      )}
      {state.kind === "error" && <Alert tone="danger">{state.message}</Alert>}
    </div>
  );
}
