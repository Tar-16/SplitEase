package com.example.splitease.domain;

import com.example.splitease.model.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RulesTest {
    private static final String A="00000000-0000-0000-0000-000000000001";
    private static final String B="00000000-0000-0000-0000-000000000002";
    private static final String C="00000000-0000-0000-0000-000000000003";
    @Test public void leftoverPaiseAreStableIndependentOfInputOrder() {
        Map<String,Long> result=Rules.equalSplit(100,Arrays.asList(C,B,A));
        assertEquals(Long.valueOf(34),result.get(A)); assertEquals(Long.valueOf(33),result.get(B));
        assertEquals(result,Rules.equalSplit(100,Arrays.asList(A,B,C)));
        assertEquals(Long.valueOf(0),Rules.equalSplit(1,Arrays.asList(A,B)).get(B));
    }
    @Test public void longMaximumSplitsExactlyWithoutOverflow() {
        long sum=0;
        for (long share : Rules.equalSplit(Long.MAX_VALUE,Arrays.asList(A,B,C)).values()) sum=Math.addExact(sum,share);
        assertEquals(Long.MAX_VALUE,sum);
    }
    @Test public void rejectsInvalidParticipantsAndAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Rules.equalSplit(1,Arrays.asList(A,A)));
        assertThrows(IllegalArgumentException.class, () -> Rules.equalSplit(0,Arrays.asList(A)));
        assertThrows(IllegalArgumentException.class, () -> Rules.equalSplit(-1,Arrays.asList(A)));
        assertThrows(IllegalArgumentException.class, () -> Rules.equalSplit(1,Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> Rules.id("1-1-1-1-1"));
    }
    @Test public void dueDatesClampToMonthEndAndRespectLeapYears() {
        Calendar calendar=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(Rules.dueDate(2024,2,31));
        assertEquals(29,calendar.get(Calendar.DAY_OF_MONTH));
        calendar.setTimeInMillis(Rules.dueDate(2025,2,31));
        assertEquals(28,calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(0,calendar.get(Calendar.HOUR_OF_DAY));
        assertThrows(IllegalArgumentException.class, () -> Rules.dueDate(2025,13,1));
    }
    @Test public void partialSettlementReducesDebtAndBalancesSumToZero() {
        Expense expense=new Expense(C,C,"Food",101,A,A,"Food",0,Arrays.asList(new ExpenseSplit(C,A,51),new ExpenseSplit(C,B,50)));
        Settlement settlement=new Settlement(C,C,B,A,20,0);
        Map<String,Long> result=Balances.calculate(Arrays.asList(expense),Arrays.asList(settlement));
        assertEquals(Long.valueOf(30),result.get(A)); assertEquals(Long.valueOf(-30),result.get(B));
    }
    @Test public void balanceOverflowIsExplicit() {
        Expense expense=new Expense(C,C,"Food",Long.MAX_VALUE,A,A,"Food",0,Arrays.asList(new ExpenseSplit(C,B,Long.MAX_VALUE)));
        assertThrows(ArithmeticException.class, () -> Balances.calculate(Arrays.asList(expense,expense),Collections.emptyList()));
    }
}
