package com.example.splitease.util;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/** Formats integer paise without a floating-point conversion. */
public final class InrFormatter {
    private InrFormatter() {}

    public static String format(long paise) {
        return formatDecimal(BigDecimal.valueOf(paise, 2));
    }
    public static String magnitude(long paise) { return formatDecimal(BigDecimal.valueOf(paise,2).abs()); }
    private static String formatDecimal(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        format.setCurrency(Currency.getInstance("INR"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }
}
