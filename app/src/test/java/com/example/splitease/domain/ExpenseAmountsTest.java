package com.example.splitease.domain;

import com.example.splitease.model.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ExpenseAmountsTest {
    @Test public void inputIsExactAndRejectsRoundingAndOverflow() {
        assertEquals(101,ExpenseAmounts.parse(" 1.01 "));
        assertEquals(1,ExpenseAmounts.parse("0.01"));
        assertEquals(Long.MAX_VALUE,ExpenseAmounts.parse("92233720368547758.07"));
        for(String invalid:Arrays.asList("", "0", "-1", "1.001", "1e2", "1,000", "NaN", ".5", "1."))
            assertThrows(IllegalArgumentException.class,() -> ExpenseAmounts.parse(invalid));
        assertThrows(ArithmeticException.class,() -> ExpenseAmounts.parse("92233720368547758.08"));
        assertEquals("0.01",ExpenseAmounts.input(1));
    }
    private Expense expense(long amount) {
        return new Expense("e","g","Lunch",amount,"a","a","Food",0,
                Arrays.asList(new ExpenseSplit("e","a",amount/2),new ExpenseSplit("e","b",amount-amount/2)));
    }
    @Test public void totalsAndPerspectiveUseCheckedPaise() {
        Expense value=expense(101);
        assertEquals(51,ExpenseAmounts.share(value,"b")); assertEquals(-51,ExpenseAmounts.impact(value,"b"));
        assertEquals(51,ExpenseAmounts.impact(value,"a")); assertEquals(0,ExpenseAmounts.impact(value,"c"));
        assertEquals(202,ExpenseAmounts.total(Arrays.asList(value,value)));
        assertThrows(ArithmeticException.class,() -> ExpenseAmounts.total(Arrays.asList(expense(Long.MAX_VALUE),expense(1))));
    }
}
