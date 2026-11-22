import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { CouponForm } from "@/components/admin/CouponForm";
import { PageTitle, StatusBadge } from "@/components/ui";
import { COUPON_STATE_TONE, couponState } from "@/lib/coupons";
import { labelled } from "@/lib/format";
import { apiGetOrNotFound } from "@/lib/server/data";
import { positiveInt } from "@/lib/search-params";
import type { Coupon } from "@/lib/types";

interface Props {
  params: Promise<{ couponId: string }>;
}

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminCoupons"))("metaTitle") };
}

export default async function EditCouponPage({ params }: Props) {
  const id = positiveInt((await params).couponId);
  if (!id) {
    notFound();
  }
  const [coupon, t] = await Promise.all([
    apiGetOrNotFound<Coupon>(`/api/orders/admin/coupons/${id}`, { auth: true }),
    getTranslations("adminCoupons"),
  ]);
  const states = labelled(COUPON_STATE_TONE, (state) => t(state));

  return (
    <>
      <Link href="/admin/coupons" className="link text-sm">
        {t("back")}
      </Link>
      <div className="mt-3">
        <PageTitle actions={<StatusBadge value={couponState(coupon)} labels={states} />}>
          {t("editTitle", { code: coupon.code })}
        </PageTitle>
      </div>
      <p className="-mt-4 mb-6 text-sm text-slate-500">
        {t("usage")}:{" "}
        {coupon.usageLimit !== null
          ? t("usageValue", { used: coupon.usedCount, limit: coupon.usageLimit })
          : t("usageUnlimited", { used: coupon.usedCount })}
      </p>
      {/* Remounted after a save so the form shows the stored values */}
      <CouponForm key={coupon.updatedAt} coupon={coupon} />
    </>
  );
}
