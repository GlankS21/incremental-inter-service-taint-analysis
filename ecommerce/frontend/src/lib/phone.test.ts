import { describe, expect, it } from "vitest";
import { formatPhone, normalizePhone, PHONE_INPUT_PATTERN } from "./phone";

describe("normalizePhone", () => {
  it("turns the usual Russian formats into +7XXXXXXXXXX", () => {
    expect(normalizePhone("+7 (999) 123-45-67")).toBe("+79991234567");
    expect(normalizePhone("8 999 123 45 67")).toBe("+79991234567");
    expect(normalizePhone("8(999)123-45-67")).toBe("+79991234567");
    expect(normalizePhone("7.999.123.45.67")).toBe("+79991234567");
    expect(normalizePhone("  9991234567 ")).toBe("+79991234567");
    expect(normalizePhone("+79991234567")).toBe("+79991234567");
  });

  it("returns other input trimmed, for the backend to reject", () => {
    expect(normalizePhone(" 12345 ")).toBe("12345");
    expect(normalizePhone("+44 20 7946 0958")).toBe("+44 20 7946 0958");
    expect(normalizePhone("4951234567")).toBe("4951234567");
    expect(normalizePhone("899912345678")).toBe("899912345678");
    expect(normalizePhone("")).toBe("");
  });
});

describe("formatPhone", () => {
  it("formats stored numbers for display", () => {
    expect(formatPhone("+79991234567")).toBe("+7 (999) 123-45-67");
  });

  it("leaves unknown formats unchanged", () => {
    expect(formatPhone("0901234567")).toBe("0901234567");
    expect(formatPhone("+7999123456")).toBe("+7999123456");
    expect(formatPhone(null)).toBeNull();
    expect(formatPhone(undefined)).toBeUndefined();
  });
});

describe("PHONE_INPUT_PATTERN", () => {
  // Browsers anchor the pattern attribute and compile it with the "v" flag
  const accepts = (value: string) => new RegExp(`^(?:${PHONE_INPUT_PATTERN})$`, "v").test(value);

  it("accepts what people type", () => {
    expect(accepts("+7 (999) 123-45-67")).toBe(true);
    expect(accepts("89991234567")).toBe(true);
    expect(accepts("8 (999) 123-45-67")).toBe(true);
  });

  it("rejects letters and too short numbers", () => {
    expect(accepts("12345")).toBe(false);
    expect(accepts("+7 999 abc 45 67")).toBe(false);
  });
});
