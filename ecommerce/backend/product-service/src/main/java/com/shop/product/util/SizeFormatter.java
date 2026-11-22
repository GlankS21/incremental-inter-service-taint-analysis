package com.shop.product.util;

import org.springframework.util.unit.DataSize;

/**
 * Human-readable sizes for Russian user-facing messages, e.g. "5 МБ" or "512 КБ".
 */
public final class SizeFormatter {

    private static final long KILOBYTE = DataSize.ofKilobytes(1).toBytes();
    private static final long MEGABYTE = DataSize.ofMegabytes(1).toBytes();

    private SizeFormatter() {
    }

    public static String format(DataSize size) {
        long bytes = size.toBytes();
        if (bytes >= MEGABYTE && bytes % MEGABYTE == 0) {
            return bytes / MEGABYTE + " МБ";
        }
        if (bytes >= KILOBYTE) {
            return bytes / KILOBYTE + " КБ";
        }
        return bytes + " Б";
    }
}
