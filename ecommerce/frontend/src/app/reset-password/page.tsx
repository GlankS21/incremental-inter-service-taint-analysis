import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { ResetPasswordForm } from "@/components/auth/ResetPasswordForm";
import { Alert } from "@/components/ui";
import { param, type SearchParams } from "@/lib/search-params";

/** Tokens are 43 URL-safe base64 characters; anything else cannot be a link of ours. */
const TOKEN = /^[A-Za-z0-9_-]{20,100}$/;

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("auth");
  // The token is in the address: keep the page out of search engines and referrers
  return { title: t("metaReset"), robots: { index: false, follow: false }, referrer: "no-referrer" };
}

export default async function ResetPasswordPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const token = param(await searchParams, "token");
  const t = await getTranslations("auth");
  return (
    <div className="container-page flex justify-center py-12">
      <div className="card w-full max-w-md p-6 sm:p-8">
        <h1 className="text-2xl font-semibold text-slate-900">{t("resetTitle")}</h1>
        <p className="mt-1 mb-6 text-sm text-slate-500">{t("resetText")}</p>
        {token && TOKEN.test(token) ? (
          <ResetPasswordForm token={token} />
        ) : (
          <Alert tone="danger">
            {t("resetMissingToken")}
            <Link href="/forgot-password" className="link mt-1 block">
              {t("requestNewLink")}
            </Link>
          </Alert>
        )}
      </div>
    </div>
  );
}
