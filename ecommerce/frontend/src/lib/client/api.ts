import { ApiError } from "@/lib/errors";
import type { ApiResponse, ErrorResponse } from "@/lib/types";

const unauthorizedHandlers = new Set<() => void>();

/**
 * Called when a call is answered 401: the session ended during the visit (revoked, or signed out in
 * another tab). The /auth/* endpoints are left out, their 401 means wrong credentials. Returns the
 * function that unsubscribes.
 */
export function onUnauthorized(handler: () => void): () => void {
  unauthorizedHandlers.add(handler);
  return () => {
    unauthorizedHandlers.delete(handler);
  };
}

/**
 * Browser-side calls. Everything goes to this site's /api/* routes, which relay to the gateway with
 * the session cookie; the response's "data" is returned, errors are thrown as ApiError.
 */
async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  headers?: Record<string, string>,
  signal?: AbortSignal,
): Promise<T> {
  const envelope = await send<T>(method, path, body, headers, signal);
  return envelope?.data as T;
}

/** The whole ApiResponse envelope (undefined for 204), errors thrown as ApiError. */
async function send<T>(
  method: string,
  path: string,
  body?: unknown,
  headers?: Record<string, string>,
  signal?: AbortSignal,
): Promise<ApiResponse<T> | undefined> {
  // Multipart bodies get their Content-Type (with the boundary) from the browser
  const multipart = body instanceof FormData;
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers: {
        Accept: "application/json",
        ...(body !== undefined && !multipart ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: multipart ? body : body !== undefined ? JSON.stringify(body) : undefined,
      credentials: "same-origin",
      cache: "no-store",
      signal,
    });
  } catch {
    // Also reached when the signal aborts the request; callers check signal.aborted
    throw new ApiError(0, { code: "NETWORK_ERROR", message: "Network error" });
  }
  if (response.status === 204) {
    return undefined;
  }
  const json = (await response.json().catch(() => null)) as ApiResponse<T> | Partial<ErrorResponse> | null;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/auth/")) {
      unauthorizedHandlers.forEach((handler) => handler());
    }
    throw new ApiError(response.status, json as Partial<ErrorResponse> | null);
  }
  return (json as ApiResponse<T> | null) ?? undefined;
}

export const api = {
  /** `signal` cancels the request (e.g. a superseded type-ahead query). */
  get: <T>(path: string, signal?: AbortSignal) => request<T>("GET", path, undefined, undefined, signal),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>("POST", path, body, headers),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  patch: <T>(path: string, body?: unknown) => request<T>("PATCH", path, body),
  delete: <T>(path: string) => request<T>("DELETE", path),
  /** multipart/form-data upload, e.g. an image file in the part "file". */
  upload: <T>(path: string, form: FormData) => request<T>("POST", path, form),
  /** Like post/put/delete, but returns the envelope, whose localized `message` is shown in a toast. */
  send: <T>(method: "POST" | "PUT" | "PATCH" | "DELETE", path: string, body?: unknown) => send<T>(method, path, body),
};
