package com.shop.auth.util;

import java.nio.charset.StandardCharsets;

/**
 * Password rules shared by registration and password change.
 * The 72 limit comes from bcrypt, which refuses to hash more than 72 bytes: the character limit
 * keeps the familiar message for ASCII passwords, the byte limit catches multi-byte characters.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;
    public static final int MAX_BYTES = 72;
    public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).+$";
    /** Keys of the Bean Validation messages in {@code messages_ru.properties}. */
    public static final String PATTERN_MESSAGE = "{auth.validation.password-pattern}";
    public static final String MAX_BYTES_MESSAGE = "{auth.validation.password-too-long}";

    private PasswordPolicy() {
    }

    /**
     * Whether bcrypt can hash the password; longer ones can never match a stored hash.
     */
    public static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
