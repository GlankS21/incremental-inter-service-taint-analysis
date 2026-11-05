package com.shop.user.util;

/**
 * Russian plural category of a count, passed as a message argument and used by a
 * {@code {n,choice,0#...|1#...|2#...}} pattern of {@code messages_ru.properties}:
 * 1 адрес, 2 адреса, 5 адресов, 11 адресов, 21 адрес.
 */
public final class RussianPlural {

    /** 1, 21, 101...: "адрес". */
    public static final int ONE = 0;
    /** 2-4, 22-24...: "адреса". */
    public static final int FEW = 1;
    /** 0, 5-20, 25-30, 111...: "адресов". */
    public static final int MANY = 2;

    private RussianPlural() {
    }

    public static int category(long count) {
        long n = Math.abs(count);
        long lastTwo = n % 100;
        long last = n % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return MANY;
        }
        if (last == 1) {
            return ONE;
        }
        if (last >= 2 && last <= 4) {
            return FEW;
        }
        return MANY;
    }
}
