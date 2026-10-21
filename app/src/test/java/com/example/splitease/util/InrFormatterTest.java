package com.example.splitease.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class InrFormatterTest {
    private String numeric(long paise) {
        return InrFormatter.format(paise).replaceAll("[^0-9.-]", "");
    }
    @Test public void formatsSinglePaiseAndNegativeAmountsExactly() {
        assertEquals("0.01", numeric(1));
        assertEquals("-1.01", numeric(-101));
        assertEquals("0.00", numeric(0));
        assertTrue(InrFormatter.format(100).contains("₹"));
    }
    @Test public void preservesPrecisionAtLongLimits() {
        assertEquals("92233720368547758.07", numeric(Long.MAX_VALUE));
        assertEquals("-92233720368547758.08", numeric(Long.MIN_VALUE));
    }
}
