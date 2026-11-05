"use client";

import { useTranslations } from "next-intl";
import { useCallback } from "react";
import { describeError } from "@/lib/errors";

/** Turns a caught error into the message shown to the user. */
export function useErrorMessage(): (error: unknown) => string {
  const t = useTranslations("errors");
  return useCallback((error: unknown) => describeError(error, t), [t]);
}
