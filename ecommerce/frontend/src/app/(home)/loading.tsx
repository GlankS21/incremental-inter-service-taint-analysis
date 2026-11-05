import { PageLoading } from "@/components/Skeletons";

/**
 * Loading screens live in route groups, next to the pages they cover, so each page gets a placeholder
 * shaped like itself. A page that calls notFound() below a loading boundary streams its not-found page
 * with status 200 and a robots "noindex" tag instead of a 404 status: the public product page therefore
 * has no loading screen, so that a missing product answers with a real 404. Order pages need a login and
 * are never indexed, so they keep theirs.
 */
export default function Loading() {
  return <PageLoading />;
}
