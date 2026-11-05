"use client";

import { useTranslations } from "next-intl";
import { useEffect, useId, useRef, useState } from "react";
import { Alert, Spinner } from "@/components/ui";

/** Payment widget of YooKassa ("Виджет ЮKassa"), loaded from YooKassa's own domain. */
export const YOOKASSA_WIDGET_SCRIPT = "https://yookassa.ru/checkout-widget/v1/checkout-widget.js";

interface CheckoutWidget {
  render(containerId?: string): Promise<void>;
  destroy(): void;
  on(event: "success" | "fail" | "complete" | "modal_close", callback: () => void): void;
}

interface CheckoutWidgetOptions {
  confirmation_token: string;
  error_callback: (error: unknown) => void;
  customization?: { modal?: boolean; colors?: Record<string, string> };
}

declare global {
  interface Window {
    YooMoneyCheckoutWidget?: new (options: CheckoutWidgetOptions) => CheckoutWidget;
  }
}

let scriptLoading: Promise<void> | null = null;

function loadWidgetScript(): Promise<void> {
  if (window.YooMoneyCheckoutWidget) {
    return Promise.resolve();
  }
  scriptLoading ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = YOOKASSA_WIDGET_SCRIPT;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      script.remove();
      scriptLoading = null;
      reject(new Error("YooKassa widget could not be loaded"));
    };
    document.head.appendChild(script);
  });
  return scriptLoading;
}

/** "token_expired", or { error: "token_expired" } depending on the widget version. */
function errorCode(error: unknown): string {
  if (typeof error === "string") {
    return error;
  }
  if (error && typeof error === "object" && "error" in error) {
    return String((error as { error: unknown }).error);
  }
  return "unknown";
}

interface Props {
  confirmationToken: string;
  /** The customer confirmed the payment (the outcome is still checked with payment-service). */
  onSuccess: () => void;
  /** The payment failed or the token expired. */
  onFail: () => void;
}

/**
 * Card form of YooKassa embedded in the page: the card data, 3-D Secure included, is entered on
 * YooKassa's side and never reaches the shop. No return_url is given, so the widget reports the end
 * of the payment with its success and fail events.
 */
export function YooKassaWidget({ confirmationToken, onSuccess, onFail }: Props) {
  const t = useTranslations("payment");
  const containerId = `yookassa-widget-${useId().replace(/[^a-zA-Z0-9-]/g, "")}`;
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // The latest callbacks, without re-creating the widget when the parent re-renders
  const callbacks = useRef({ onSuccess, onFail });
  useEffect(() => {
    callbacks.current = { onSuccess, onFail };
  }, [onSuccess, onFail]);

  useEffect(() => {
    let widget: CheckoutWidget | null = null;
    let disposed = false;
    const finish = (callback: () => void) => {
      widget?.destroy();
      widget = null;
      callback();
    };

    loadWidgetScript()
      .then(() => {
        const Widget = window.YooMoneyCheckoutWidget;
        if (disposed || !Widget) {
          return;
        }
        widget = new Widget({
          confirmation_token: confirmationToken,
          error_callback: (cause) => {
            const code = errorCode(cause);
            if (code === "token_expired" || code === "invalid_token") {
              finish(() => callbacks.current.onFail());
            } else {
              setError(t("widgetError", { code }));
            }
          },
          customization: { colors: { control_primary: "#1d4ed8", background: "#ffffff" } },
        });
        widget.on("success", () => finish(() => callbacks.current.onSuccess()));
        widget.on("fail", () => finish(() => callbacks.current.onFail()));
        return widget.render(containerId).then(() => {
          if (!disposed) {
            setLoading(false);
          }
        });
      })
      .catch(() => {
        if (!disposed) {
          setError(t("widgetUnavailable"));
        }
      });

    return () => {
      disposed = true;
      widget?.destroy();
    };
  }, [confirmationToken, containerId, t]);

  return (
    <div className="flex flex-col gap-3">
      {loading && !error && (
        <div className="flex items-center justify-center gap-3 py-10 text-sm text-slate-500" role="status">
          <Spinner className="h-5 w-5" />
          {t("widgetLoading")}
        </div>
      )}
      {error && <Alert tone="danger">{error}</Alert>}
      <div id={containerId} className="min-h-0" />
    </div>
  );
}
