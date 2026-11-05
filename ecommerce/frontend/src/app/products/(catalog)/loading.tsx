import { ProductGridSkeleton } from "@/components/Skeletons";

/** Same layout as the catalogue (filters on the left from lg up), so nothing jumps once it loads. */
export default function Loading() {
  return (
    <div className="container-page py-8">
      <div className="mb-6 h-8 w-56 animate-pulse rounded bg-slate-200" />
      <div className="grid gap-8 lg:grid-cols-[240px_1fr]">
        <div className="card hidden h-96 animate-pulse bg-slate-100 lg:block" />
        <ProductGridSkeleton count={9} />
      </div>
    </div>
  );
}
