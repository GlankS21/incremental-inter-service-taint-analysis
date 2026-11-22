import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { RefreshResult } from "@/lib/server/token-refresh";

const refreshTokens = vi.fn<(token: string, forwardedFor: string | null) => Promise<RefreshResult>>();
vi.mock("@/lib/server/token-refresh", () => ({ refreshTokens: (token: string, forwardedFor: string | null) => refreshTokens(token, forwardedFor) }));

const { proxy } = await import("./proxy");

/** A visit with an expired access token and a refresh token. */
function visit(path: string): NextRequest {
  return new NextRequest(new URL(path, "http://shop.test"), { headers: { cookie: "shop_rt=refresh-token" } });
}

describe("proxy", () => {
  beforeEach(() => {
    refreshTokens.mockReset();
  });

  it("sends a visitor whose session was revoked to the login page", async () => {
    refreshTokens.mockResolvedValue({ kind: "rejected" });
    const response = await proxy(visit("/orders?page=2"));
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://shop.test/login?next=%2Forders%3Fpage%3D2");
  });

  it("keeps the session while auth-service is down instead of asking to sign in", async () => {
    refreshTokens.mockResolvedValue({ kind: "unavailable" });
    const page = await proxy(visit("/orders"));
    expect(page.status).toBe(503);
    expect(page.headers.get("location")).toBeNull();
    expect(page.headers.get("x-middleware-rewrite")).toBe("http://shop.test/unavailable");
    expect(page.cookies.get("shop_rt")).toBeUndefined();

    const call = await proxy(visit("/api/cart"));
    expect(call.status).toBe(503);
    expect(((await call.json()) as { code: string }).code).toBe("SERVICE_UNAVAILABLE");
  });
});
