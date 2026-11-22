/** Russian phone numbers, stored by the services as "+7XXXXXXXXXX". */

/**
 * Pattern of the phone inputs: the formats people type, e.g. "8 999 123-45-67" or "+7 (999) 123-45-67".
 * Parentheses and "-" are escaped because browsers compile the attribute with the "v" flag.
 */
export const PHONE_INPUT_PATTERN = String.raw`[+]?[0-9\s\(\)\-]{10,18}`;

/**
 * "8 (999) 123-45-67", "7 999 1234567" or "9991234567" → "+79991234567". Anything else is returned
 * trimmed, so that the backend reports it as invalid.
 */
export function normalizePhone(input: string): string {
  const trimmed = input.trim();
  const compact = trimmed.replace(/[\s().-]/g, "");
  let digits: string | null = null;
  if (/^\+7\d{10}$/.test(compact)) {
    digits = compact.slice(2);
  } else if (/^[78]\d{10}$/.test(compact)) {
    digits = compact.slice(1);
  } else if (/^9\d{9}$/.test(compact)) {
    digits = compact;
  }
  return digits === null ? trimmed : `+7${digits}`;
}

/** "+79991234567" → "+7 (999) 123-45-67"; other values are returned unchanged. */
export function formatPhone(phone: string): string;
export function formatPhone(phone: string | null | undefined): string | null | undefined;
export function formatPhone(phone: string | null | undefined): string | null | undefined {
  const match = phone ? /^\+7(\d{3})(\d{3})(\d{2})(\d{2})$/.exec(phone) : null;
  return match ? `+7 (${match[1]}) ${match[2]}-${match[3]}-${match[4]}` : phone;
}
