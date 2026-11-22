package com.shop.order.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Amounts written by the service into texts shown to customers, the Russian way: {@code 1 234,50 ₽}
 * (non-breaking spaces). Other currencies keep their ISO code: {@code 19,99 USD}.
 */
public final class MoneyFormat {

    static final char NBSP = ' ';
    private static final String RUB = "RUB";
    private static final String RUBLE_SIGN = "₽";

    private MoneyFormat() {
    }

    public static String format(BigDecimal amount, String currency) {
        if (amount == null) {
            return "—";
        }
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru-RU"));
        symbols.setGroupingSeparator(NBSP);
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        String value = format.format(amount.setScale(2, RoundingMode.HALF_UP));
        String unit = currency == null || RUB.equals(currency) ? RUBLE_SIGN : currency;
        return value + NBSP + unit;
    }
}
