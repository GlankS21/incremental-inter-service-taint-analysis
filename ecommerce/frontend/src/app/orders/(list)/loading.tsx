import { TableSkeleton } from "@/components/Skeletons";

export default function Loading() {
  return (
    <div className="container-page py-8">
      <TableSkeleton rows={5} />
    </div>
  );
}
