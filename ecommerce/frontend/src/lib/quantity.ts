/** Text of a quantity field while typing: digits only, and it may be empty for a moment. */
export function quantityDraft(input: string): string {
  return input.replace(/\D/g, "").slice(0, 4);
}

/** Quantity of a typed value, between 1 and `max`, applied on blur and on submit; empty → 1. */
export function clampQuantity(draft: string, max: number): number {
  const value = Number.parseInt(draft, 10);
  return Number.isFinite(value) ? Math.min(max, Math.max(1, value)) : 1;
}
