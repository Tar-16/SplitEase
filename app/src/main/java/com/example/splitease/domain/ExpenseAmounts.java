package com.example.splitease.domain;

import com.example.splitease.model.*;
import java.math.BigDecimal;
import java.util.List;

/** Exact INR input and aggregate calculations; never round user input or use floating point. */
public final class ExpenseAmounts {
    private ExpenseAmounts() {}
    public static long parse(String input) {
        String value=input==null ? "" : input.trim();
        if(!value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Enter rupees with up to two decimals");
        long paise=new BigDecimal(value).movePointRight(2).longValueExact();
        Rules.positive(paise); return paise;
    }
    public static String input(long paise) { return BigDecimal.valueOf(paise,2).toPlainString(); }
    public static long total(List<Expense> expenses) {
        long total=0; for(Expense expense:expenses) total=Math.addExact(total,expense.amountPaise); return total;
    }
    public static long share(Expense expense,String userId) {
        for(ExpenseSplit split:expense.splits) if(split.userId.equals(userId)) return split.sharePaise;
        return 0;
    }
    public static long impact(Expense expense,String userId) {
        return Math.subtractExact(expense.payerId.equals(userId)?expense.amountPaise:0L,share(expense,userId));
    }
}
