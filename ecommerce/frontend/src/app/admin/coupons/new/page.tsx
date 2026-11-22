import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { CouponForm } from "@/components/admin/CouponForm";
import { PageTitle } from "@/components/ui";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminCoupons"))("new") };
}

export default async function NewCouponPage() {
  const t = await getTranslations("adminCoupons");
  return (
    <>
      <Link href="/admin/coupons" className="link text-sm">
        {t("back")}
      </Link>
      <div className="mt-3">
        <PageTitle>{t("new")}</PageTitle>
      </div>
      <CouponForm />
    </>
  );
}
