"use client";

import Link from "next/link";
import messages from "../../messages/ru.json";
import { DEFAULT_LOCALE } from "@/i18n/config";
import "./globals.css";

const t = messages.globalError;

/**
 * Last resort for errors thrown by the root layout itself (the 500 page of the site). It replaces the
 * whole document, so it renders without the layout and its providers: texts come straight from the
 * message file.
 */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <html lang={DEFAULT_LOCALE}>
      <body className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
        <main className="card flex w-full max-w-md flex-col items-center gap-4 px-6 py-12 text-center">
          <p className="text-5xl font-semibold text-slate-300">500</p>
          <h1 className="text-xl font-semibold text-slate-900">{t.title}</h1>
          <p className="text-sm text-slate-500">{t.text}</p>
          {error.digest && <p className="text-xs text-slate-400">{t.code.replace("{digest}", error.digest)}</p>}
          <div className="mt-2 flex flex-wrap justify-center gap-3">
            <button
              type="button"
              onClick={reset}
              className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700"
            >
              {t.retry}
            </button>
            <Link
              href="/"
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              {t.home}
            </Link>
          </div>
        </main>
      </body>
    </html>
  );
}
