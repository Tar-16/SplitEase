package com.example.splitease.domain;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
public class ReceiptCorpusTest {
 @Test public void labeledReceiptTypesHaveNoIncorrectSuggestions()throws Exception {
  int correct=0,uncertain=0,incorrect=0;
  try(BufferedReader reader=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/receipt-fixtures.tsv"),StandardCharsets.UTF_8))){String row;while((row=reader.readLine())!=null){if(row.startsWith("#")||row.isEmpty())continue;String[] fields=row.split("\t",3);ReceiptTotalDetector.Detection result=ReceiptTotalDetector.detect(fields[2].replace("\\n","\n"),true);
   if(result.amountPaise==null)uncertain++;else if(!fields[1].equals(result.amountPaise.toString()))incorrect++;else correct++;
   assertEquals(fields[0]+"\n"+result.debugReport,fields[1].equals("UNCERTAIN")?null:Long.valueOf(fields[1]),result.amountPaise);
  }}
  System.out.println("SYNTHETIC TEXT CORPUS correct="+correct+" incorrect="+incorrect+" uncertain="+uncertain);
 }
 @Test public void separateColumnsSkewAndOverlappingTilesAssociateWithoutMixingAdjacentTaxRows(){
  List<ReceiptTotalDetector.Line> lines=Arrays.asList(
   new ReceiptTotalDetector.Line("NET AMOUNT",10,100,200,120,.08f),new ReceiptTotalDetector.Line("Rs 1000.00",600,147,750,167,.08f),
   new ReceiptTotalDetector.Line("CGST 2.5%",10,145,200,165,.08f),new ReceiptTotalDetector.Line("Rs 25.00",600,192,750,212,.08f),
   new ReceiptTotalDetector.Line("SGST 2.5%",10,190,200,210,.08f),new ReceiptTotalDetector.Line("Rs 25.00",600,237,750,257,.08f),
   new ReceiptTotalDetector.Line("TOTAL",10,250,200,270,.08f),new ReceiptTotalDetector.Line("Rs 1050.00",600,297,750,317,.08f),
   new ReceiptTotalDetector.Line("Rs 1050.00",601,298,751,318,.08f));
  ReceiptTotalDetector.Detection result=ReceiptTotalDetector.detect(lines,true);assertEquals(result.debugReport,Long.valueOf(105000),result.amountPaise);assertEquals(ReceiptTotalDetector.Reliability.HIGH,result.reliability);
 }
 @Test public void overlappingWordFragmentsAndSplitCentsDoNotCreateExtraAmounts(){
  List<ReceiptTotalDetector.Line> lines=Arrays.asList(new ReceiptTotalDetector.Line("TOTAL",0,100,110,140),new ReceiptTotalDetector.Line("Rs",130,100,170,140),new ReceiptTotalDetector.Line("942.",200,100,280,140),new ReceiptTotalDetector.Line("9",200,100,220,140),new ReceiptTotalDetector.Line("82",290,100,330,140));
  assertEquals(Long.valueOf(94282),ReceiptTotalDetector.detect(lines,true).amountPaise);
 }
 @Test public void debugDataIsOptInAndReliabilityRequiresIndependentEvidenceForCorrectedDigits(){
  ReceiptTotalDetector.Detection normal=ReceiptTotalDetector.detect("GRAND TOTAL Rs 250.00");assertNull(normal.debugReport);assertEquals("GRAND TOTAL",normal.supportingLabel);assertEquals(ReceiptTotalDetector.Reliability.HIGH,normal.reliability);
  ReceiptTotalDetector.Detection repaired=ReceiptTotalDetector.detect("GRAND TOTAL Rs 25O.00\nUPI Rs 250.00",true);assertEquals(Long.valueOf(25000),repaired.amountPaise);assertEquals(ReceiptTotalDetector.Reliability.REVIEW,repaired.reliability);assertTrue(repaired.debugReport.contains("independent payment"));
 }
}
