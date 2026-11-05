package com.shop.notification.template;

/**
 * Russian plural forms of a noun after a number: {@code 1 товар}, {@code 2 товара}, {@code 5 товаров}.
 * <p>
 * The form depends on the last two digits: 11 to 14 always take the "many" form, otherwise a last
 * digit of 1 takes the "one" form and 2 to 4 the "few" form.
 */
public final class RussianPlural {

    private RussianPlural() {
    }

    /**
     * @param one  form after 1, 21, 101... ({@code товар})
     * @param few  form after 2 to 4, 22 to 24... ({@code товара})
     * @param many form after 0, 5 to 20, 25 to 30... ({@code товаров})
     * @return the form agreeing with {@code count}
     */
    public static String select(long count, String one, String few, String many) {
        long n = Math.abs(count);
        long lastTwo = n % 100;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return many;
        }
        long last = n % 10;
        if (last == 1) {
            return one;
        }
        if (last >= 2 && last <= 4) {
            return few;
        }
        return many;
    }

    /**
     * @return the count followed by the agreeing form, e.g. {@code 3 товара}
     */
    public static String withCount(long count, String one, String few, String many) {
        return count + " " + select(count, one, few, many);
    }
}
