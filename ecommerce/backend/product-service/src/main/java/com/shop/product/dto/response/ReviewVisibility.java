package com.shop.product.dto.response;

/**
 * Whether a review is shown with its product.
 */
public enum ReviewVisibility {

    /** The product is in the catalogue: the review is listed with it. */
    PUBLISHED,

    /** The product was withdrawn from the catalogue (draft or archived): its reviews are not shown. */
    HIDDEN
}
