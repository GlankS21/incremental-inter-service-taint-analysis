import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import type { ReactNode } from "react";
import { formatAddress } from "@/components/address/AddressFields";
import { OrderTable } from "@/components/order/OrderTable";
import { Alert, Badge, PageTitle } from "@/components/ui";
import { ApiError } from "@/lib/errors";
import { formatDate, formatDateTime } from "@/lib/format";
import { formatPhone } from "@/lib/phone";
import { apiGet, apiGetOrNotFound } from "@/lib/server/data";
import { isUuid } from "@/lib/search-params";
import type { Account, Address, OrderSummary, PageResponse, UserProfile } from "@/lib/types";

interface Props {
  params: Promise<{ accountId: string }>;
}

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("adminUser"))("metaTitle") };
}

/** "missing" when user-service has no profile yet (it is created on the first visit of the account). */
async function loadProfile(id: string): Promise<UserProfile | "missing" | null> {
  try {
    return await apiGet<UserProfile>(`/api/users/${id}`, { auth: true });
  } catch (error) {
    return error instanceof ApiError && error.status === 404 ? "missing" : null;
  }
}

/** One customer as seen by the shop: credentials (auth-service), profile and addresses (user-service), orders. */
export default async function AdminAccountPage({ params }: Props) {
  const { accountId } = await params;
  if (!isUuid(accountId)) {
    notFound();
  }
  const [account, profile, addresses, orders, t] = await Promise.all([
    apiGetOrNotFound<Account>(`/api/auth/admin/accounts/${accountId}`, { auth: true }),
    loadProfile(accountId),
    apiGet<Address[]>(`/api/users/${accountId}/addresses`, { auth: true }).catch(() => null),
    apiGet<PageResponse<OrderSummary>>(`/api/orders/admin?userId=${accountId}&size=10&sort=createdAt,desc`, { auth: true }).catch(
      () => null,
    ),
    getTranslations("adminUser"),
  ]);
  const fullName =
    profile && profile !== "missing" ? [profile.lastName, profile.firstName].filter(Boolean).join(" ") : "";

  return (
    <>
      <Link href="/admin/accounts" className="link text-sm">
        {t("back")}
      </Link>
      <div className="mt-3">
        <PageTitle>{fullName || account.email}</PageTitle>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="card p-5" aria-labelledby="account-title">
          <h2 id="account-title" className="mb-3 font-semibold text-slate-900">
            {t("account")}
          </h2>
          <dl className="flex flex-col gap-2 text-sm">
            <Row label={t("email")}>{account.email}</Row>
            <Row label={t("roles")}>
              <span className="flex flex-wrap gap-1">
                {account.roles.includes("ROLE_ADMIN") && <Badge tone="info">{t("admin")}</Badge>}
                <Badge>{t("customer")}</Badge>
              </span>
            </Row>
            <Row label={t("status")}>
              {account.enabled ? <Badge tone="success">{t("enabled")}</Badge> : <Badge tone="danger">{t("disabled")}</Badge>}
            </Row>
            <Row label={t("lastLogin")}>{formatDateTime(account.lastLoginAt)}</Row>
            <Row label={t("registered")}>{formatDateTime(account.createdAt)}</Row>
          </dl>
        </section>

        <section className="card p-5" aria-labelledby="profile-title">
          <h2 id="profile-title" className="mb-3 font-semibold text-slate-900">
            {t("profile")}
          </h2>
          {profile === null ? (
            <Alert tone="warning">{t("profileUnavailable")}</Alert>
          ) : profile === "missing" ? (
            <p className="text-sm text-slate-500">{t("noProfile")}</p>
          ) : (
            <dl className="flex flex-col gap-2 text-sm">
              <Row label={t("name")}>{fullName || t("notSet")}</Row>
              <Row label={t("phone")}>{formatPhone(profile.phoneNumber) ?? t("notSet")}</Row>
              <Row label={t("birthDate")}>{profile.dateOfBirth ? formatDate(profile.dateOfBirth) : t("notSet")}</Row>
            </dl>
          )}
        </section>
      </div>

      <section className="card mt-6 p-5" aria-labelledby="addresses-title">
        <h2 id="addresses-title" className="mb-3 font-semibold text-slate-900">
          {t("addresses")}
        </h2>
        {addresses === null ? (
          <Alert tone="warning">{t("addressesUnavailable")}</Alert>
        ) : addresses.length === 0 ? (
          <p className="text-sm text-slate-500">{t("noAddresses")}</p>
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2">
            {addresses.map((address) => (
              <li key={address.id} className="rounded-lg border border-slate-200 p-3 text-sm">
                <p className="font-medium text-slate-900">
                  {address.recipientName}
                  {address.defaultAddress && (
                    <span className="ml-2">
                      <Badge tone="info">{t("defaultAddress")}</Badge>
                    </span>
                  )}
                </p>
                <p className="text-slate-600">{formatPhone(address.phoneNumber)}</p>
                <p className="mt-1 text-slate-600">{formatAddress(address)}</p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card mt-6 overflow-x-auto" aria-labelledby="orders-title">
        <div className="flex flex-wrap items-center justify-between gap-2 p-4">
          <h2 id="orders-title" className="font-semibold text-slate-900">
            {t("orders")}
          </h2>
          {orders && orders.totalElements > orders.content.length && (
            <Link href={`/admin/orders?userId=${accountId}`} className="link text-sm">
              {t("allOrders")}
            </Link>
          )}
        </div>
        {orders === null ? (
          <p className="p-4 text-sm text-slate-500">{t("ordersUnavailable")}</p>
        ) : orders.content.length === 0 ? (
          <p className="p-4 pt-0 text-sm text-slate-500">{t("noOrders")}</p>
        ) : (
          <OrderTable orders={orders.content} />
        )}
      </section>
    </>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap justify-between gap-2">
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-right text-slate-900">{children}</dd>
    </div>
  );
}
