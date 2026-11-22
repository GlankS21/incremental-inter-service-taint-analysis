import type { AddressFields } from "./types";

/**
 * Idempotency-Key of an order (order-service accepts [A-Za-z0-9_-]{8,100}). crypto.randomUUID exists
 * only in secure contexts (HTTPS or localhost); a shop opened over plain HTTP gets 128 random bits
 * from getRandomValues instead.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Whether the address typed at checkout goes to the address book. A retried order (after an
 * out-of-stock error, say) does not save the same address a second time.
 */
export function shouldSaveAddress(requested: boolean, address: AddressFields, alreadySaved: AddressFields | null): boolean {
  return requested && (alreadySaved === null || !sameAddress(address, alreadySaved));
}

function sameAddress(a: AddressFields, b: AddressFields): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)] as (keyof AddressFields)[]);
  return [...keys].every((key) => (a[key] ?? null) === (b[key] ?? null));
}
