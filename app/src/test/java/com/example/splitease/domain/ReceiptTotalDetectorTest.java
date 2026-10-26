package com.example.splitease.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReceiptTotalDetectorTest {
 private void amount(long expected,String text){ReceiptTotalDetector.Detection value=ReceiptTotalDetector.detect(text);assertEquals(ReceiptTotalDetector.Status.SUGGESTED,value.status);assertEquals(Long.valueOf(expected),value.amountPaise);}
 private void manual(String text){assertNull(ReceiptTotalDetector.detect(text).amountPaise);}
 @Test public void payableLabelsBeatItemsSubtotalCashAndChange(){amount(99900,"Rice 200.00\nSUB TOTAL 1100.00\nGRAND TOTAL Rs. 999.00\nCASH TENDERED 2000.00\nCHANGE 1001.00");amount(12345678,"NET AMOUNT INR 1,23,456.78");amount(125050,"TOTAL PAYABLE ₹1,250.50");amount(45000,"TOTAL 450/-");}
 @Test public void indianAndInternationalGroupingAndSingleDecimalAreExact(){amount(123456789,"GRAND TOTAL 12,34,567.89");amount(12345678,"NET AMOUNT 123,456.78");amount(9990,"TOTAL Rs 99.9");amount(Long.MAX_VALUE,"TOTAL 92233720368547758.07");}
 @Test public void wrappedLabelsAndDuplicateSameValuesWork(){amount(79900,"GRAND TOTAL\nINR 799.00\nTOTAL 900.00");amount(12300,"GRAND TOTAL 123\nTOTAL PAYABLE 123.00");}
 @Test public void ambiguousConflictingAndUnlabelledAmountsStayManual(){manual("item 999\nCASH 1000\nCHANGE 1");manual("TOTAL 999\nTOTAL 1000");manual("GRAND TOTAL 900\nNET AMOUNT 800");manual("TOTAL 2 999");manual("TOTAL QTY 3\nSUBTOTAL 900\nTOTAL TAX 100");}
 @Test public void malformedNegativeZeroAndOverflowAmountsAreRejected(){for(String text:new String[]{"TOTAL -123.00","TOTAL (123.00)","TOTAL 0.00","TOTAL 1,2,3.45","TOTAL 123.456","TOTAL 92233720368547758.08","GRAND TOTAL unreadable\nTOTAL 100"}){manual(text);}manual(null);}
}
