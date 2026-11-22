import { describe, expect, it } from "vitest";
import { formatAddress } from "./address";
import type { AddressFields } from "./types";

const ADDRESS: AddressFields = {
  recipientName: "Иван Петров",
  phoneNumber: "+79991234567",
  addressLine1: "ул. Мира, д. 5, кв. 12",
  addressLine2: "подъезд 2, этаж 3",
  ward: "мкр. Сходня",
  district: "Московская обл.",
  city: "Химки",
  postalCode: "141400",
  countryCode: "RU",
};

describe("formatAddress", () => {
  it("lists the parts from the postal code down to the flat", () => {
    expect(formatAddress(ADDRESS)).toBe(
      "141400, Московская обл., Химки, мкр. Сходня, ул. Мира, д. 5, кв. 12, подъезд 2, этаж 3",
    );
  });

  it("skips empty optional parts", () => {
    expect(
      formatAddress({ ...ADDRESS, postalCode: null, district: " ", ward: null, addressLine2: "", city: "Москва" }),
    ).toBe("Москва, ул. Мира, д. 5, кв. 12");
  });
});
