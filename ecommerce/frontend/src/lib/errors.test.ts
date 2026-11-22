import { describe, expect, it } from "vitest";
import { ApiError, describeError, type ErrorTranslator } from "./errors";
import { isKnownFailure } from "./payment-failure";

const t: ErrorTranslator = (key, values) => (values ? `${key}:${values.detail}` : key);

describe("describeError", () => {
  it("translates transport and HTTP errors", () => {
    expect(describeError(new TypeError("fetch failed"), t)).toBe("network");
    expect(describeError(new ApiError(401, null), t)).toBe("unauthorized");
    expect(describeError(new ApiError(400, { message: "Bad sort" }), t)).toBe("badRequest:Bad sort");
    expect(describeError(new ApiError(400, { violations: [{ field: "a", message: "b" }] }), t)).toBe("invalidFields");
    expect(describeError(new ApiError(503, null), t)).toBe("unavailable");
  });

  it("shows business-rule messages of the services as they are", () => {
    expect(describeError(new ApiError(422, { message: "Этот заказ больше нельзя оплатить" }), t)).toBe(
      "Этот заказ больше нельзя оплатить",
    );
  });

  it("does not show the English message of the site's own API proxy", () => {
    expect(describeError(new ApiError(400, { code: "INVALID_PATH", message: "Invalid path" }), t)).toBe("unexpected");
  });

  it("does not show a bare status code when a conflict has no message", () => {
    expect(describeError(new ApiError(409, null), t)).toBe("unexpected");
    expect(describeError(new ApiError(422, { message: "" }), t)).toBe("unexpected");
    expect(describeError(new ApiError(400, null), t)).toBe("unexpected");
  });
});

describe("isKnownFailure", () => {
  it("recognizes decline reasons with a translation", () => {
    expect(isKnownFailure("insufficient_funds")).toBe(true);
    expect(isKnownFailure("constructor")).toBe(false);
    expect(isKnownFailure(null)).toBe(false);
  });
});
