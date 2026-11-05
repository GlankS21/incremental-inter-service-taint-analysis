package com.shop.product.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Image formats accepted for upload, recognised by their magic bytes rather than by the declared
 * content type or the file name, both of which the client controls.
 */
public enum ImageType {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif");

    /** Number of leading bytes {@link #detect(byte[])} needs to recognise every type. */
    public static final int HEADER_LENGTH = 12;

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] GIF87A_SIGNATURE = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89A_SIGNATURE = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF_SIGNATURE = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP_SIGNATURE = "WEBP".getBytes(StandardCharsets.US_ASCII);

    private final String contentType;
    private final String extension;

    ImageType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /**
     * @param header the first bytes of the file (at least {@link #HEADER_LENGTH} for WebP)
     */
    public static Optional<ImageType> detect(byte[] header) {
        if (startsWith(header, 0, PNG_SIGNATURE)) {
            return Optional.of(PNG);
        }
        if (startsWith(header, 0, JPEG_SIGNATURE)) {
            return Optional.of(JPEG);
        }
        if (startsWith(header, 0, GIF87A_SIGNATURE) || startsWith(header, 0, GIF89A_SIGNATURE)) {
            return Optional.of(GIF);
        }
        // RIFF container: "RIFF", 4 bytes of chunk size, then the form type "WEBP"
        if (startsWith(header, 0, RIFF_SIGNATURE) && startsWith(header, 8, WEBP_SIGNATURE)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /**
     * Type of a stored file, from the extension given to it at upload.
     */
    public static Optional<ImageType> fromExtension(String extension) {
        return Arrays.stream(values()).filter(type -> type.extension.equals(extension)).findFirst();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] signature) {
        if (data == null || data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
