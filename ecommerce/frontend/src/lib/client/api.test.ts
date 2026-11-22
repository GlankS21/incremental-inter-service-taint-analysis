import { afterEach, describe, expect, it, vi } from "vitest";
import { api, onUnauthorized } from "./api";
import { ApiError } from "@/lib/errors";

function respond(status: number, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })),
  );
}

describe("api session handling", () => {
  let unsubscribe = () => {};

  afterEach(() => {
    unsubscribe();
    vi.unstubAllGlobals();
  });

  it("reports a session that ended during the visit", async () => {
    const handler = vi.fn();
    unsubscribe = onUnauthorized(handler);
    respond(401, { status: 401, code: "UNAUTHORIZED", message: "Требуется вход" });

    await expect(api.get("/orders")).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("leaves wrong credentials on the sign-in endpoints to the form", async () => {
    const handler = vi.fn();
    unsubscribe = onUnauthorized(handler);
    respond(401, { status: 401, code: "UNAUTHORIZED", message: "Неверный пароль" });

    await expect(api.post("/auth/login", {})).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
  });

  it("ignores other errors and stops after unsubscribing", async () => {
    const handler = vi.fn();
    onUnauthorized(handler)();
    respond(401, null);
    await expect(api.get("/cart")).rejects.toBeInstanceOf(ApiError);

    unsubscribe = onUnauthorized(handler);
    respond(403, null);
    await expect(api.get("/cart")).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
  });
});
