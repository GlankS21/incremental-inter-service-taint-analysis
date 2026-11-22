import { describe, expect, it } from "vitest";
import { awaitsOnlinePayment, isCancellable, isSettling, needsPaymentForm } from "./orders";
import type { Order, Payment } from "./types";

const order = (status: Order["status"], paymentMethod: Order["paymentMethod"] = "ONLINE") =>
  ({ status, paymentMethod }) as Order;

describe("isCancellable", () => {
  it("allows cancelling until the order is shipped", () => {
    expect(isCancellable("PENDING")).toBe(true);
    expect(isCancellable("AWAITING_PAYMENT")).toBe(true);
    expect(isCancellable("PAID")).toBe(true);
    expect(isCancellable("SHIPPED")).toBe(false);
    expect(isCancellable("CANCELLED")).toBe(false);
  });
});

describe("awaitsOnlinePayment", () => {
  it("is only true for online orders waiting for their payment", () => {
    expect(awaitsOnlinePayment(order("AWAITING_PAYMENT"))).toBe(true);
    expect(awaitsOnlinePayment(order("AWAITING_PAYMENT", "COD"))).toBe(false);
    expect(awaitsOnlinePayment(order("PAID"))).toBe(false);
  });
});

describe("isSettling", () => {
  it("keeps refreshing during the saga and while a refund is processed", () => {
    expect(isSettling(order("PENDING"), null)).toBe(true);
    expect(isSettling(order("CANCELLED"), { status: "REFUND_PENDING" } as Payment)).toBe(true);
    expect(isSettling(order("CANCELLED"), { status: "REFUNDED" } as Payment)).toBe(false);
  });
});

describe("needsPaymentForm", () => {
  it("sends an unpaid order back to the payment form", () => {
    expect(needsPaymentForm(order("AWAITING_PAYMENT"), { status: "PENDING" } as Payment)).toBe(true);
    expect(needsPaymentForm(order("AWAITING_PAYMENT"), { status: "FAILED" } as Payment)).toBe(true);
    expect(needsPaymentForm(order("AWAITING_PAYMENT"), null)).toBe(true);
  });

  it("waits for the confirmation of a captured payment", () => {
    expect(needsPaymentForm(order("AWAITING_PAYMENT"), { status: "SUCCEEDED" } as Payment)).toBe(false);
    expect(needsPaymentForm(order("PENDING"), null)).toBe(false);
    expect(needsPaymentForm(order("PAID"), null)).toBe(false);
  });
});
