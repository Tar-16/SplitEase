package com.example.splitease.domain;

import com.example.splitease.model.*;
import java.util.*;

/** Largest remaining debtor/creditor first, UUID tie-break. Greedy, not a minimum-payment proof. */
public final class SettlementCalculator {
    private SettlementCalculator() {}
    private static final class Remaining {
        final String id; long amount;
        Remaining(String id,long amount) { this.id=id; this.amount=amount; }
    }
    public static List<Transfer> suggest(Map<String,Long> balances) {
        Comparator<Remaining> order=Comparator.<Remaining>comparingLong(v -> v.amount).reversed().thenComparing(v -> v.id);
        PriorityQueue<Remaining> debtors=new PriorityQueue<>(order),creditors=new PriorityQueue<>(order);
        long debts=0,credits=0;
        for(Map.Entry<String,Long> entry:balances.entrySet()) {
            Rules.id(entry.getKey()); long amount=entry.getValue();
            if(amount<0) { long magnitude=Math.negateExact(amount); debts=Math.addExact(debts,magnitude); debtors.add(new Remaining(entry.getKey(),magnitude)); }
            if(amount>0) { credits=Math.addExact(credits,amount); creditors.add(new Remaining(entry.getKey(),amount)); }
        }
        if(debts!=credits) throw new IllegalArgumentException("Balances do not conserve money");
        List<Transfer> result=new ArrayList<>();
        while(!debtors.isEmpty()) {
            Remaining debtor=debtors.remove(),creditor=creditors.remove(); long amount=Math.min(debtor.amount,creditor.amount);
            result.add(new Transfer(debtor.id,creditor.id,amount)); debtor.amount=Math.subtractExact(debtor.amount,amount); creditor.amount=Math.subtractExact(creditor.amount,amount);
            if(debtor.amount>0) debtors.add(debtor); if(creditor.amount>0) creditors.add(creditor);
        }
        return Collections.unmodifiableList(result);
    }
    public static long suggestedLimit(Map<String,Long> balances,String payer,String payee) {
        for(Transfer transfer:suggest(balances)) if(transfer.payerId.equals(payer) && transfer.payeeId.equals(payee)) return transfer.amountPaise;
        return 0;
    }
    /** Edits may retain/reduce old value. Increases must fit the pair's capacity with old record removed. */
    public static long editLimit(List<Expense> expenses,List<Settlement> records,Settlement old) {
        List<Settlement> remaining=new ArrayList<>(); for(Settlement value:records) if(!value.id.equals(old.id)) remaining.add(value);
        Map<String,Long> base=Balances.calculate(expenses,remaining);
        long payer=base.getOrDefault(old.payerId,0L), payee=base.getOrDefault(old.payeeId,0L);
        long capacity=payer<0 && payee>0 ? Math.min(Math.negateExact(payer),payee):0;
        return Math.max(old.amountPaise,capacity);
    }
    public static boolean potentialDuplicate(List<Settlement> records,Settlement value) {
        for(Settlement old:records) if(!old.id.equals(value.id) && old.groupId.equals(value.groupId) && old.payerId.equals(value.payerId)
                && old.payeeId.equals(value.payeeId) && old.amountPaise==value.amountPaise && old.settlementDate/86400000L==value.settlementDate/86400000L) return true;
        return false;
    }
}
