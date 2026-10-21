package com.example.splitease.domain;

import com.example.splitease.model.*;
import java.util.*;

/** Positive balance means owed to the member; recurring bills are deliberately absent. */
public final class Balances {
    private Balances() {}
    public static Map<String, Long> calculate(List<Expense> expenses, List<Settlement> settlements) {
        Map<String, Long> balances = new TreeMap<>();
        for (Expense expense : expenses) {
            add(balances, expense.payerId, expense.amountPaise);
            for (ExpenseSplit split : expense.splits) add(balances, split.userId, Math.negateExact(split.sharePaise));
        }
        for (Settlement settlement : settlements) {
            add(balances, settlement.payerId, settlement.amountPaise);
            add(balances, settlement.payeeId, Math.negateExact(settlement.amountPaise));
        }
        return Collections.unmodifiableMap(balances);
    }
    private static void add(Map<String, Long> values, String id, long amount) {
        values.put(id, Math.addExact(values.getOrDefault(id, 0L), amount));
    }
}
