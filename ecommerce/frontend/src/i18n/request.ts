import { getRequestConfig } from "next-intl/server";
import { DEFAULT_LOCALE, TIME_ZONE } from "./config";

/**
 * The shop has a single language: every request is rendered in Russian, without a locale prefix
 * in the URLs.
 */
export default getRequestConfig(async () => ({
  locale: DEFAULT_LOCALE,
  timeZone: TIME_ZONE,
  messages: (await import(`../../messages/${DEFAULT_LOCALE}.json`)).default,
}));
