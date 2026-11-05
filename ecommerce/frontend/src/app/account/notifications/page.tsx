import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { NotificationPreferencesForm } from "@/components/account/NotificationPreferencesForm";
import { Pagination, pageIndex } from "@/components/Pagination";
import { Badge, ButtonLink, EmptyState, PageTitle } from "@/components/ui";
import { formatDateTime } from "@/lib/format";
import { apiGet, keepPageInRange, query } from "@/lib/server/data";
import { param, type SearchParams } from "@/lib/search-params";
import type { Notification, NotificationType, PageResponse } from "@/lib/types";

/** Notifications asking to review delivered products: they lead to "Мои отзывы". */
const REVIEW_TYPES: NotificationType[] = ["REVIEW_REQUEST", "REVIEW_REMINDER"];

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("notifications"))("title") };
}

export default async function NotificationsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const page = pageIndex(param(await searchParams, "page"));
  const [notifications, t, tType] = await Promise.all([
    apiGet<PageResponse<Notification>>(`/api/notifications${query({ page, size: 10 })}`, { auth: true }),
    getTranslations("notifications"),
    getTranslations("notificationType"),
  ]);
  keepPageInRange(page, notifications, "/account/notifications", {});

  return (
    <>
      <PageTitle>{t("title")}</PageTitle>
      <p className="-mt-4 mb-6 text-sm text-slate-500">{t("intro")}</p>
      <NotificationPreferencesForm />
      {notifications.content.length === 0 ? (
        <EmptyState title={t("empty")} />
      ) : (
        <ul className="flex flex-col gap-3">
          {notifications.content.map((notification) => (
            <li key={notification.id} className="card">
              <details className="group p-4">
                <summary className="flex cursor-pointer list-none flex-wrap items-center gap-2">
                  <Badge tone="info">{tType(notification.type)}</Badge>
                  {notification.channel === "IN_APP" && <Badge>{t("inApp")}</Badge>}
                  <span className="flex-1 font-medium text-slate-900">{notification.subject}</span>
                  <time className="text-xs text-slate-500">{formatDateTime(notification.sentAt ?? notification.createdAt)}</time>
                  {REVIEW_TYPES.includes(notification.type) && (
                    <ButtonLink href="/account/reviews" size="sm" className="w-full sm:w-auto">
                      {t("rateAction")}
                    </ButtonLink>
                  )}
                </summary>
                <pre className="mt-3 font-sans text-sm leading-relaxed whitespace-pre-wrap text-slate-700">{notification.body ?? t("hiddenBody")}</pre>
              </details>
            </li>
          ))}
        </ul>
      )}
      <Pagination page={notifications.page} totalPages={notifications.totalPages} basePath="/account/notifications" params={{}} />
    </>
  );
}
