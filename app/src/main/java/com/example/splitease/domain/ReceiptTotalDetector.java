package com.example.splitease.domain;

import java.util.*;
import java.util.regex.*;

/** Conservative label-based suggestions only; never a payment or expense creation instruction. */
public final class ReceiptTotalDetector {
 public enum Status { SUGGESTED, NOT_FOUND, AMBIGUOUS }
 public static final class Detection {
  public final Status status; public final Long amountPaise;
  private Detection(Status status,Long amount){this.status=status;amountPaise=amount;}
 }
 private static final Pattern EXCLUDED=Pattern.compile("\\b(SUB\\s*TOTAL|CASH|TENDERED|CHANGE|TOTAL\\s+(?:TAX|GST|ITEMS?|QTY|SAVINGS?|DISCOUNT))\\b");
 private static final Pattern LABEL=Pattern.compile("\\b(GRAND\\s+TOTAL|NET\\s+AMOUNT|TOTAL\\s+PAYABLE|AMOUNT\\s+PAYABLE|NET\\s+PAYABLE|TOTAL)\\b");
 private static final Pattern NUMBER=Pattern.compile("(?<![\\w.,])(?:[0-9]{1,3}(?:,[0-9]{2})*,[0-9]{3}|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?(?![\\w.,])");
 private ReceiptTotalDetector() {}
 private static int rank(String label){return label.equals("TOTAL")?1:label.startsWith("GRAND")?3:2;}
 private static List<Long> amounts(String value) {
  List<Long> result=new ArrayList<>();Matcher matcher=NUMBER.matcher(value);
  while(matcher.find()) {
   String before=value.substring(0,matcher.start()).trim();if(before.endsWith("-")||before.endsWith("("))return Collections.emptyList();
   try{long amount=ExpenseAmounts.parse(matcher.group().replace(",",""));result.add(amount);}catch(IllegalArgumentException|ArithmeticException invalid){return Collections.emptyList();}
  }
  return result;
 }
 public static Detection detect(String raw) {
  if(raw==null)return new Detection(Status.NOT_FOUND,null);
  String[] lines=raw.toUpperCase(Locale.ROOT).replace('\u00a0',' ').split("\\R");
  int highest=0;Map<Integer,Set<Long>> candidates=new HashMap<>();Set<Long> strong=new HashSet<>();boolean ambiguous=false;
  for(int index=0;index<lines.length;index++) {
   String line=lines[index].trim();if(EXCLUDED.matcher(line).find())continue;Matcher label=LABEL.matcher(line);if(!label.find())continue;
   int rank=rank(label.group().replaceAll("\\s+"," "));highest=Math.max(highest,rank);
   String tail=line.substring(label.end());List<Long> values=amounts(tail);
   if(values.isEmpty() && !NUMBER.matcher(tail).find() && index+1<lines.length && lines[index+1].trim().matches("(?:INR|RS\\.?|₹)?[ :]*[0-9][0-9, .]*(?:/-)?"))values=amounts(lines[index+1]);
   if(values.size()!=1){if(values.size()>1)ambiguous=true;continue;}
   candidates.computeIfAbsent(rank,key->new HashSet<>()).add(values.get(0));if(rank>1)strong.add(values.get(0));
  }
  Set<Long> best=candidates.getOrDefault(highest,Collections.emptySet());
  if(strong.size()>1||best.size()>1||ambiguous)return new Detection(Status.AMBIGUOUS,null);
  return best.size()==1?new Detection(Status.SUGGESTED,best.iterator().next()):new Detection(Status.NOT_FOUND,null);
 }
}
