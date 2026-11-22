package com.shop.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The comment and the author name are trimmed by the service; a blank comment is stored as none.
 */
public record ReviewRequest(

        @Schema(description = "Оценка в звёздах, от 1 до 5", example = "5")
        @NotNull @Min(1) @Max(5)
        Integer rating,

        @Schema(example = "Отличный телефон, батареи хватает на два дня")
        @Size(max = 2000)
        String comment,

        @Schema(description = "Имя, которое показывается рядом с отзывом", example = "Иван Петров")
        @NotBlank @Size(max = 60)
        String authorName
) {
}
