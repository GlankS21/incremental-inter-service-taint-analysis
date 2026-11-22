import { NextIntlClientProvider } from "next-intl";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import messages from "../../../messages/ru.json";
import { ModalDialog } from "@/components/Modal";
import { RatingInput } from "@/components/product/RatingInput";
import { ReviewForm } from "@/components/product/ReviewForm";
import { ReviewTabs } from "@/components/review/AccountReviews";
import { PendingCountBadge } from "@/components/review/PendingReviewsBadge";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/account/reviews",
}));

/**
 * The tests run in node without a DOM: components are rendered to HTML on the "server", and the opening
 * tags of the markup are read back with their attributes.
 */
interface Tag {
  name: string;
  attrs: Record<string, string>;
  /** Text up to the closing tag of the same name (enough for buttons, headings, text areas). */
  text: string;
}

function render(element: ReactElement): string {
  return renderToStaticMarkup(
    <NextIntlClientProvider locale="ru" messages={messages} timeZone="Europe/Moscow">
      {element}
    </NextIntlClientProvider>,
  );
}

function tags(html: string, attribute: string, value?: string): Tag[] {
  const found: Tag[] = [];
  for (const match of html.matchAll(/<([a-z0-9]+)((?:\s+[^\s=>/]+(?:="[^"]*")?)*)\s*\/?>/gi)) {
    const attrs: Record<string, string> = {};
    for (const attr of match[2].matchAll(/([^\s=]+)(?:="([^"]*)")?/g)) {
      attrs[attr[1]] = attr[2] ?? "";
    }
    if (attribute in attrs && (value === undefined || attrs[attribute] === value)) {
      const start = (match.index ?? 0) + match[0].length;
      const end = html.indexOf(`</${match[1]}>`, start);
      found.push({ name: match[1], attrs, text: textOf(html.slice(start, end < 0 ? start : end)) });
    }
  }
  return found;
}

function one(html: string, attribute: string, value?: string): Tag {
  const found = tags(html, attribute, value);
  expect(found).toHaveLength(1);
  return found[0];
}

function textOf(html: string): string {
  return html.replace(/<[^>]+>/g, "").replace(/&quot;/g, '"').replace(/&amp;/g, "&");
}

describe("review dialog", () => {
  const dialog = () =>
    render(
      <ModalDialog title="Отзыв о товаре «Смартфон»" onClose={() => {}}>
        <ReviewForm bare productId={7} review={null} defaultName="Анна" initialRating={4} onSaved={() => {}} onCancel={() => {}} />
      </ModalDialog>,
    );

  it("is a modal dialog named by its title, with a labelled close button", () => {
    const html = dialog();
    const element = one(html, "role", "dialog");
    expect(element.attrs["aria-modal"]).toBe("true");
    expect(one(html, "id", element.attrs["aria-labelledby"]).text).toBe("Отзыв о товаре «Смартфон»");
    expect(one(html, "data-modal-close").attrs["aria-label"]).toBe("Закрыть");
  });

  it("opens with the stars chosen on the product row and the suggested name", () => {
    const html = dialog();
    const stars = tags(html, "role", "radio");
    expect(stars.map((star) => star.attrs["aria-checked"])).toEqual(["false", "false", "false", "true", "false"]);
    expect(one(html, "name", "authorName").attrs.value).toBe("Анна");
    expect(one(html, "type", "submit").text).toBe("Опубликовать");
    // A review not written yet cannot be deleted
    expect(textOf(html)).not.toContain("Удалить отзыв");
  });

  it("edits an existing review with its own rating", () => {
    const html = render(
      <ReviewForm
        bare
        productId={7}
        review={{ id: 1, productId: 7, authorName: "Анна", rating: 2, comment: "Так себе", verifiedPurchase: true, createdAt: "", updatedAt: "" }}
        defaultName="Анна"
        initialRating={5}
        onSaved={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(one(html, "aria-checked", "true").attrs["aria-label"]).toBe("2 звезды");
    expect(one(html, "name", "comment").text).toBe("Так себе");
    expect(one(html, "type", "submit").text).toBe("Сохранить отзыв");
    expect(textOf(html)).toContain("Удалить отзыв");
  });
});

describe("star picker", () => {
  it("is a labelled radio group with only the chosen star in the tab order", () => {
    const html = render(<RatingInput value={3} onChange={() => {}} label="Оцените «Чехол»" />);
    expect(one(html, "role", "radiogroup").attrs["aria-label"]).toBe("Оцените «Чехол»");
    const stars = tags(html, "role", "radio");
    expect(stars.map((star) => star.attrs.tabindex)).toEqual(["-1", "-1", "0", "-1", "-1"]);
    expect(stars.map((star) => star.attrs["aria-label"])).toEqual(["1 звезда", "2 звезды", "3 звезды", "4 звезды", "5 звёзд"]);
  });

  it("puts the first star in the tab order when nothing is chosen", () => {
    const html = render(<RatingInput value={0} onChange={() => {}} label="Оценка" />);
    expect(tags(html, "role", "radio").map((star) => star.attrs.tabindex)).toEqual(["0", "-1", "-1", "-1", "-1"]);
    expect(tags(html, "aria-checked", "true")).toHaveLength(0);
  });
});

describe("tabs of «Мои отзывы»", () => {
  it("counts the products waiting for a review in the first tab", () => {
    const html = render(<ReviewTabs active="pending" pendingCount={3} onSelect={() => {}} />);
    expect(one(html, "role", "tablist").attrs["aria-label"]).toBe("Разделы отзывов");
    const tabs = tags(html, "role", "tab");
    expect(tabs.map((tab) => tab.text)).toEqual(["Ожидают отзыва (3)", "Мои отзывы"]);
    expect(tabs.map((tab) => tab.attrs["aria-selected"])).toEqual(["true", "false"]);
    expect(tabs[0].attrs["aria-controls"]).toBe("reviews-panel-pending");
  });

  it("leaves the count out while it is unknown, and keeps only the selected tab in the tab order", () => {
    const html = render(<ReviewTabs active="mine" pendingCount={null} onSelect={() => {}} />);
    const tabs = tags(html, "role", "tab");
    expect(tabs[0].text).toBe("Ожидают отзыва");
    expect(tabs.map((tab) => tab.attrs.tabindex)).toEqual(["-1", "0"]);
  });
});

describe("pending reviews badge", () => {
  it("shows the count, with a grammatical label for screen readers", () => {
    const label = (count: number) => one(render(<PendingCountBadge count={count} />), "class", "sr-only").text;
    const visible = (count: number) => one(render(<PendingCountBadge count={count} />), "aria-hidden", "true").text;
    expect(visible(1)).toBe("1");
    expect(label(1)).toBe("1 товар ждёт отзыва");
    expect(label(3)).toBe("3 товара ждут отзыва");
    expect(label(5)).toBe("5 товаров ждут отзыва");
    expect(visible(150)).toBe("99+");
    expect(label(150)).toBe("150 товаров ждут отзыва");
  });

  it("is absent without pending reviews", () => {
    expect(render(<PendingCountBadge count={0} />)).toBe("");
    expect(render(<PendingCountBadge count={null} />)).toBe("");
  });
});
