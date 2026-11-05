import { afterEach, describe, expect, it, vi } from "vitest";
import { newIdempotencyKey, shouldSaveAddress } from "./checkout";
import type { AddressFields } from "./types";

/** Format accepted by order-service for the Idempotency-Key header. */
const KEY_FORMAT = /^[A-Za-z0-9_-]{8,100}$/;

describe("newIdempotencyKey", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses randomUUID where the browser has it", () => {
    const key = newIdempotencyKey();
    expect(key).toMatch(KEY_FORMAT);
    expect(newIdempotencyKey()).not.toBe(key);
  });

  it("falls back to getRandomValues on plain-HTTP origins, where randomUUID is missing", () => {
    const real = globalThis.crypto;
    vi.stubGlobal("crypto", { getRandomValues: real.getRandomValues.bind(real) });
    const key = newIdempotencyKey();
    expect(key).toMatch(KEY_FORMAT);
    expect(newIdempotencyKey()).not.toBe(key);
  });
});

const ADDRESS: AddressFields = {
  recipientName: "Иван Петров",
  phoneNumber: "+79991234567",
  addressLine1: "ул. Мира, д. 5",
  city: "Химки",
  countryCode: "RU",
};

describe("shouldSaveAddress", () => {
  it("saves a new address only when asked", () => {
    expect(shouldSaveAddress(true, ADDRESS, null)).toBe(true);
    expect(shouldSaveAddress(false, ADDRESS, null)).toBe(false);
  });

  it("does not save the same address again when the order is retried", () => {
    expect(shouldSaveAddress(true, ADDRESS, { ...ADDRESS })).toBe(false);
  });

  it("saves an address changed after the failed attempt", () => {
    expect(shouldSaveAddress(true, { ...ADDRESS, city: "Москва" }, ADDRESS)).toBe(true);
  });
});
