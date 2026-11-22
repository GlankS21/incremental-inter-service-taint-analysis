package com.shop.product.dto.request;

import com.shop.product.util.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Terms are trimmed, lower-cased and de-duplicated by the service, which then requires at least two
 * distinct terms that belong to no other group.
 */
public record SynonymGroupRequest(

        @Schema(description = "Взаимозаменяемые слова или фразы (буквы, цифры, пробел, дефис)",
                example = "[\"ноутбук\", \"лэптоп\", \"laptop\"]")
        @NotNull @Size(min = 2, max = 20)
        List<@NotBlank @Size(max = 60)
                @Pattern(regexp = ValidationPatterns.SYNONYM_TERM, message = ValidationPatterns.SYNONYM_TERM_MESSAGE)
                String> terms
) {
}
