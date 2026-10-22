package com.example.splitease.domain;
import com.example.splitease.model.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettlementCalculatorTest {
    private final String a="00000000-0000-0000-0000-000000000001",b="00000000-0000-0000-0000-000000000002",c="00000000-0000-0000-0000-000000000003",d="00000000-0000-0000-0000-000000000004";
    @Test public void deterministicGreedyConservesEveryPaiseAndSkipsZero() {
        Map<String,Long> balances=new LinkedHashMap<>(); balances.put(d,51L); balances.put(c,50L); balances.put(b,-50L); balances.put(a,-51L);
        List<Transfer> first=SettlementCalculator.suggest(balances);
        assertEquals(a,first.get(0).payerId); assertEquals(d,first.get(0).payeeId); assertEquals(51,first.get(0).amountPaise);
        Map<String,Long> reordered=new TreeMap<>(balances); List<Transfer> second=SettlementCalculator.suggest(reordered);
        for(int i=0;i<first.size();i++) { Transfer transfer=first.get(i); assertEquals(transfer.payerId,second.get(i).payerId); assertEquals(transfer.payeeId,second.get(i).payeeId);
            balances.put(transfer.payerId,Math.addExact(balances.get(transfer.payerId),transfer.amountPaise)); balances.put(transfer.payeeId,Math.subtractExact(balances.get(transfer.payeeId),transfer.amountPaise)); }
        for(long amount:balances.values()) assertEquals(0,amount); assertTrue(SettlementCalculator.suggest(balances).isEmpty());
        Map<String,Long> tied=new HashMap<>();tied.put(b,-50L);tied.put(a,-50L);tied.put(d,50L);tied.put(c,50L);
        assertEquals(a,SettlementCalculator.suggest(tied).get(0).payerId); assertEquals(c,SettlementCalculator.suggest(tied).get(0).payeeId);
    }
    @Test public void partialTransfersAndLimitsUseActualBalances() {
        Map<String,Long> balances=new HashMap<>();balances.put(a,-101L);balances.put(b,101L);
        assertEquals(101,SettlementCalculator.suggestedLimit(balances,a,b)); assertEquals(0,SettlementCalculator.suggestedLimit(balances,b,a));
        balances.put(a,-1L);balances.put(b,1L);assertEquals(1,SettlementCalculator.suggest(balances).get(0).amountPaise);
    }
    @Test public void imbalanceAndOverflowFailInsteadOfWrapping() {
        assertThrows(IllegalArgumentException.class,() -> SettlementCalculator.suggest(Collections.singletonMap(a,1L)));
        Map<String,Long> values=new HashMap<>();values.put(a,Long.MIN_VALUE);values.put(b,Long.MAX_VALUE);values.put(c,1L);
        assertThrows(ArithmeticException.class,() -> SettlementCalculator.suggest(values));
        values.put(a,-Long.MAX_VALUE); values.put(b,Long.MAX_VALUE); values.remove(c);
        assertEquals(Long.MAX_VALUE,SettlementCalculator.suggest(values).get(0).amountPaise);
    }
    @Test public void duplicateHeuristicAndCorrectionCapAreExplicit() {
        Settlement old=new Settlement("s","g",a,b,20,100);
        assertTrue(SettlementCalculator.potentialDuplicate(Arrays.asList(old),new Settlement("t","g",a,b,20,200)));
        assertFalse(SettlementCalculator.potentialDuplicate(Arrays.asList(old),new Settlement("s","g",a,b,20,100)));
        assertFalse(SettlementCalculator.potentialDuplicate(Arrays.asList(old),new Settlement("t","g",a,b,20,86400000)));
        Expense expense=new Expense("e","g","test",100,b,b,"Food",0,Collections.singletonList(new ExpenseSplit("e",a,100)));
        assertEquals(100,SettlementCalculator.editLimit(Arrays.asList(expense),Arrays.asList(old),old));
        assertEquals(20,SettlementCalculator.editLimit(Collections.emptyList(),Arrays.asList(old),old));
    }
}
