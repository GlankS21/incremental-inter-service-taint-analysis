import { TableSkeleton } from "@/components/Skeletons";

/** Rendered inside the account layout, next to the navigation. */
export default function Loading() {
  return <TableSkeleton rows={4} />;
}
