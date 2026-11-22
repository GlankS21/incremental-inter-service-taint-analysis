"use client";

import { useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ToastProvider } from "@/components/toast";
import { api, onUnauthorized } from "@/lib/client/api";
import { loginUrl } from "@/lib/safe-redirect";
import type { Session } from "@/lib/session";
import type { Cart, PendingReviewCount } from "@/lib/types";

const SessionContext = createContext<Session | null>(null);

interface CartState {
  /** Units in the cart; null while unknown (signed out, or not loaded yet). */
  count: number | null;
  refresh: () => Promise<void>;
}

const CartContext = createContext<CartState>({ count: null, refresh: async () => {} });

interface PendingReviewsState {
  /** Products received and not reviewed yet; null while unknown (signed out, or not loaded yet). */
  count: number | null;
  refresh: () => Promise<void>;
}

const PendingReviewsContext = createContext<PendingReviewsState>({ count: null, refresh: async () => {} });

/**
 * The session is decided on the server (from the HttpOnly cookie) and handed down; it changes only
 * through a navigation or router.refresh() after login/logout.
 */
export function Providers({ session, children }: { session: Session | null; children: ReactNode }) {
  const [count, setCount] = useState<number | null>(null);
  const [pendingReviews, setPendingReviews] = useState<number | null>(null);
  const userId = session?.userId;
  const router = useRouter();

  const refresh = useCallback(async () => {
    if (!userId) {
      setCount(null);
      return;
    }
    try {
      const cart = await api.get<Cart>("/cart");
      setCount(cart.totalQuantity);
    } catch {
      // The badge is optional; the cart page shows the actual error
    }
  }, [userId]);

  useEffect(() => {
    // Loads the badge for the signed-in user (and clears it after logout)
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  const refreshPendingReviews = useCallback(async () => {
    if (!userId) {
      setPendingReviews(null);
      return;
    }
    try {
      const result = await api.get<PendingReviewCount>("/reviews/pending/count");
      setPendingReviews(result.count);
    } catch {
      // The badge is optional; "Мои отзывы" shows the actual error
    }
  }, [userId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refreshPendingReviews();
  }, [refreshPendingReviews]);

  useEffect(() => {
    if (!userId) {
      return;
    }
    // The session ended during the visit: the header stops showing the account and the user signs
    // in again, then comes back to this page
    let handled = false;
    return onUnauthorized(() => {
      if (handled) {
        return;
      }
      handled = true;
      router.replace(loginUrl(window.location.pathname + window.location.search));
      router.refresh();
    });
  }, [userId, router]);

  const cart = useMemo(() => ({ count: userId ? count : null, refresh }), [userId, count, refresh]);
  const reviews = useMemo(
    () => ({ count: userId ? pendingReviews : null, refresh: refreshPendingReviews }),
    [userId, pendingReviews, refreshPendingReviews],
  );
  return (
    <SessionContext.Provider value={session}>
      <CartContext.Provider value={cart}>
        <PendingReviewsContext.Provider value={reviews}>
          <ToastProvider>{children}</ToastProvider>
        </PendingReviewsContext.Provider>
      </CartContext.Provider>
    </SessionContext.Provider>
  );
}

export function useSession(): Session | null {
  return useContext(SessionContext);
}

export function useCart(): CartState {
  return useContext(CartContext);
}

/** Number of products waiting for the customer's review (badge of "Мои отзывы"). */
export function usePendingReviews(): PendingReviewsState {
  return useContext(PendingReviewsContext);
}
