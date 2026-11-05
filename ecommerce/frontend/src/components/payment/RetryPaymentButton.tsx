"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Alert, Button } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useErrorMessage } from "@/lib/client/use-error-message";

interface Props {
  orderId: string;
  label: string;
  size?: "md" | "lg";
}

/**
 * Starts a new payment attempt after a declined or abandoned one and opens the card form of the
 * order, e.g. to pay with another card.
 */
export function RetryPaymentButton({ orderId, label, size = "md" }: Props) {
  const errorMessage = useErrorMessage();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const retry = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/payments/orders/${orderId}/retry`);
      router.push(`/orders/${orderId}/payment`);
    } catch (caught) {
      setError(errorMessage(caught));
      setSubmitting(false);
    }
  };

  return (
    <div className="flex w-full flex-col items-center gap-3">
      <Button onClick={retry} loading={submitting} size={size}>
        {label}
      </Button>
      {error && <Alert tone="danger">{error}</Alert>}
    </div>
  );
}
