package com.shop.auth.dto.request;

import com.shop.auth.util.PasswordPolicy;
import com.shop.auth.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @Schema(description = "Токен из ссылки в письме для сброса пароля")
        @NotBlank @Size(max = 100)
        String token,

        @Schema(description = "Новый пароль: от 8 до 72 символов, хотя бы одна буква и одна цифра", example = "NewSecret456")
        @NotBlank
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.PATTERN_MESSAGE)
        @MaxUtf8Bytes(max = PasswordPolicy.MAX_BYTES, message = PasswordPolicy.MAX_BYTES_MESSAGE)
        String newPassword
) {
}
