import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("auth");
  return { title: t("metaForgot") };
}

export default async function ForgotPasswordPage() {
  const t = await getTranslations("auth");
  return (
    <div className="container-page flex justify-center py-12">
      <div className="card w-full max-w-md p-6 sm:p-8">
        <h1 className="text-2xl font-semibold text-slate-900">{t("forgotTitle")}</h1>
        <p className="mt-1 mb-6 text-sm text-slate-500">{t("forgotText")}</p>
        <ForgotPasswordForm />
      </div>
    </div>
  );
}
