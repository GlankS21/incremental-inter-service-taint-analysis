import type { AddressFields } from "./types";

/**
 * One-line address in the Russian order, from the postal code down to the flat:
 * "141400, Московская обл., Химки, мкр. Сходня, ул. Мира, д. 5, кв. 12, подъезд 2".
 * "district" holds the region and "ward" the city district.
 */
export function formatAddress(address: AddressFields): string {
  return [address.postalCode, address.district, address.city, address.ward, address.addressLine1, address.addressLine2]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(", ");
}
