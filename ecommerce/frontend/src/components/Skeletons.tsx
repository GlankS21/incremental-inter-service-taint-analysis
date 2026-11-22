import { getTranslations } from "next-intl/server";
import { Spinner } from "@/components/ui";

/** Placeholders shown by the loading.tsx files while a page's data is fetched on the server. */

export async function PageLoading() {
  const t = await getTranslations("common");
  return (
    <div role="status" className="flex items-center justify-center gap-3 py-24 text-sm text-slate-500">
      <Spinner className="h-5 w-5 text-brand-600" />
      {t("loading")}
    </div>
  );
}

function Bar({ className }: { className: string }) {
  return <div className={`animate-pulse rounded bg-slate-200 ${className}`} />;
}

export async function ProductGridSkeleton({ count = 8 }: { count?: number }) {
  const t = await getTranslations("common");
  return (
    <div role="status" aria-label={t("loading")} className="grid grid-cols-2 gap-4 md:grid-cols-3">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="card overflow-hidden">
          <div className="aspect-square animate-pulse bg-slate-200" />
          <div className="flex flex-col gap-2 p-4">
            <Bar className="h-3 w-1/3" />
            <Bar className="h-4 w-full" />
            <Bar className="h-5 w-1/2" />
          </div>
        </div>
      ))}
    </div>
  );
}

export async function TableSkeleton({ rows = 6 }: { rows?: number }) {
  const t = await getTranslations("common");
  return (
    <div role="status" aria-label={t("loading")} className="flex flex-col gap-4">
      <Bar className="h-8 w-48" />
      <div className="card flex flex-col gap-3 p-4">
        {Array.from({ length: rows }, (_, index) => (
          <Bar key={index} className="h-6 w-full" />
        ))}
      </div>
    </div>
  );
}
