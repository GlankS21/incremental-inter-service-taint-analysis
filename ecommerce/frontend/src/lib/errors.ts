import type { ErrorResponse, FieldViolation } from "./types";

/** An error answered by the API (gateway or service), in the backend's ErrorResponse format. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly violations: FieldViolation[];
  readonly correlationId?: string;
  /** The message of the response body, localized by the services; absent for an empty or non-JSON body. */
  readonly serverMessage?: string;

  constructor(status: number, body: Partial<ErrorResponse> | null) {
    super(body?.message || `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.serverMessage = body?.message || undefined;
    this.code = body?.code ?? "UNKNOWN";
    this.violations = body?.violations ?? [];
    this.correlationId = body?.correlationId;
  }

  /** Validation message of one request field, e.g. "shippingAddress.phoneNumber". */
  fieldError(field: string): string | undefined {
    return this.violations.find((violation) => violation.field === field)?.message;
  }
}

/**
 * Code of the 400 answered by this site's own /api proxy for a malformed path; its English message
 * is for logs only.
 */
export const INVALID_PATH_CODE = "INVALID_PATH";

/** Keys of the "errors" messages; see {@link describeError}. */
export type ErrorMessageKey =
  | "network"
  | "invalidFields"
  | "badRequest"
  | "unauthorized"
  | "forbidden"
  | "notFound"
  | "tooManyRequests"
  | "unavailable"
  | "unexpected";

/** The translator of the "errors" namespace, e.g. useTranslations("errors"). */
export type ErrorTranslator = (key: ErrorMessageKey, values?: { detail: string }) => string;

/**
 * Translated message shown to the user. Business-rule messages (409, 422) are already localized by
 * the services and are shown as they are.
 */
export function describeError(error: unknown, t: ErrorTranslator): string {
  if (!(error instanceof ApiError)) {
    return t("network");
  }
  switch (error.status) {
    case 400:
      if (error.violations.length > 0) {
        return t("invalidFields");
      }
      return error.serverMessage && error.code !== INVALID_PATH_CODE
        ? t("badRequest", { detail: error.serverMessage })
        : t("unexpected");
    case 401:
      return t("unauthorized");
    case 403:
      return t("forbidden");
    case 404:
      return t("notFound");
    case 409:
    case 422:
      return error.serverMessage ?? t("unexpected");
    case 429:
      return t("tooManyRequests");
    case 503:
    case 504:
      return t("unavailable");
    default:
      return t("unexpected");
  }
}
