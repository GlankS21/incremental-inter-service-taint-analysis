import type messages from "../../messages/ru.json";
import type { DEFAULT_LOCALE } from "./config";

// Message keys are checked by the compiler
declare module "next-intl" {
  interface AppConfig {
    Locale: typeof DEFAULT_LOCALE;
    Messages: typeof messages;
  }
}
