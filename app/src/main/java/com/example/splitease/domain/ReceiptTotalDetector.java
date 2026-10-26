package com.example.splitease.domain;

import java.util.*;
import java.util.regex.*;

/** Deterministic evidence ranking. Geometry is in image pixels; monetary arithmetic is checked paise. */
public final class ReceiptTotalDetector {
 public enum Status { SUGGESTED, NOT_FOUND, AMBIGUOUS }
 public enum Reliability { HIGH, REVIEW, UNCERTAIN }
 public static final class Line {
  public final String text; public final float left,top,right,bottom,slope;
  public Line(String text,float left,float top,float right,float bottom){this(text,left,top,right,bottom,0);}
  public Line(String text,float left,float top,float right,float bottom,float slope){this.text=text==null?"":text;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.slope=slope;}
  float height(){return Math.max(1,bottom-top);} float x(){return (left+right)/2;} float y(){return (top+bottom)/2;}
 }
 public static final class Detection {
  public final Status status; public final Long amountPaise; public final Reliability reliability; public final String supportingLabel;
  /** Only populated when the caller explicitly requests private debug diagnostics. Never log this. */
  public final String debugReport;
  private Detection(Status status,Long amount,Reliability reliability,String label,String debug){this.status=status;amountPaise=amount;this.reliability=reliability;supportingLabel=label;debugReport=debug;}
 }
 private static final Pattern LABEL=Pattern.compile("\\b(GRAND\\s+TOTAL|FINAL\\s+TOTAL|NET\\s+PAYABLE|AMOUNT\\s+PAYABLE|TOTAL\\s+PAYABLE|TOTAL\\s+AMOUNT|BILL\\s+AMOUNT|AMOUNT\\s+CHARGED|AMOUNT\\s+PAID|PAID\\s+AMOUNT|NET\\s+AMOUNT|TOTAL)\\b");
 private static final Pattern EXCLUDED=Pattern.compile("\\b(SUB\\s*TOTAL|SUBTOT|CGST[A-Z]*|SGST[A-Z]*|IGST[A-Z]*|UTGST[A-Z]*|TOTAL\\s+(?:TAX|GST|ITEMS?|QTY|QUANTITY|SAVINGS?|DISCOUNT)|DISCOUNT|COUPON|MRP|CASH|CHANGE|TENDERED|QUANTITY|QTY|PREVIOUS\\s+BALANCE|LOYALTY|POINTS|VAT|CESS|TAXABLE|SERVICE\\s+CHARGE|GSTIN|INVOICE|PHONE|MOBILE|REF\\.?\\s*NO|DATE)\\b");
 private static final Pattern TAX=Pattern.compile("\\b(CGST[A-Z]*|SGST[A-Z]*|IGST[A-Z]*|UTGST[A-Z]*|VAT|TAX|SERVICE\\s+CHARGE)\\b");
 private static final Pattern PAYMENT=Pattern.compile("\\b(UPI|CARD|AMOUNT\\s+PAID|PAID\\s+AMOUNT|PAYMENT\\s+(?:RECEIVED|RECEIPT))\\b");
 private static final Pattern TOKEN=Pattern.compile("(?<![A-Z0-9])[-+]?\\(?[0-9OIL][0-9OIL,.]*\\)?(?:/-)?(?![A-Z0-9])");
 private static final Pattern GROUPED=Pattern.compile("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{2})*,[0-9]{3}|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?");
 private static final class NumberValue {long value;boolean currency,decimal,corrected;NumberValue(long value,boolean currency,boolean decimal,boolean corrected){this.value=value;this.currency=currency;this.decimal=decimal;this.corrected=corrected;}}
 private static final class Row {String text;float y,height;Row(String text,float y,float height){this.text=text;this.y=y;this.height=height;}}
 private static final class Candidate {long amount;String label;int row,score,rank;boolean corrected,corroborated,arithmeticAgreement;List<String> reasons=new ArrayList<>();Candidate(NumberValue n,String label,int row){amount=n.value;this.label=label;this.row=row;rank=rank(label);score=rank+8+(n.currency?4:0)+(n.decimal?3:0);corrected=n.corrected;if(corrected){score-=20;reasons.add("OCR numeric correction requires independent agreement");}if(rank<=70&&!n.currency&&!n.decimal){score-=20;reasons.add("bare integer is weak monetary evidence");}}}
 private ReceiptTotalDetector(){}
 private static String normalize(String text){return text.toUpperCase(Locale.ROOT).replace('\u00a0',' ').replaceAll("\\bT[O0]TA[1IL]\\b","TOTAL").replaceAll("\\bT0TAL\\b","TOTAL").replaceAll("\\bAM0UNT\\b","AMOUNT").replaceAll("\\bPAYAB1E\\b","PAYABLE");}
 private static String observationKey(String text){String value=normalize(text);return value.matches("[0-9OIL,]+\\.[0-9OIL]+")?value.replace('O','0').replace('I','1').replace('L','1'):value;}
 private static int rank(String label){if(label.matches("GRAND TOTAL|FINAL TOTAL|NET PAYABLE|AMOUNT PAYABLE|TOTAL PAYABLE"))return 100;if(label.matches("TOTAL AMOUNT|BILL AMOUNT|AMOUNT CHARGED"))return 90;if(label.matches("AMOUNT PAID|PAID AMOUNT"))return 85;return label.equals("NET AMOUNT")?80:70;}
 private static List<NumberValue> numbers(String text){
  if(text.contains("?"))return Collections.emptyList();
  text=text.replaceAll("\\b(INR|RS\\.?)\\s*(?=[0-9OIL])","$1 ");
  List<NumberValue> values=new ArrayList<>();Matcher matcher=TOKEN.matcher(text);
  while(matcher.find()){
   String token=matcher.group(),before=text.substring(0,matcher.start()).trim(),after=text.substring(matcher.end()).trim();
   // Do not extract pieces of dates, times, ranges, percentages, IDs or malformed decimals.
   char previous=matcher.start()==0?' ':text.charAt(matcher.start()-1),next=matcher.end()==text.length()?' ':text.charAt(matcher.end());
   if(("/-.,".indexOf(previous)>=0&&!before.endsWith("RS."))||(previous==':'&&matcher.start()>1&&Character.isDigit(text.charAt(matcher.start()-2)))||"/:".indexOf(next)>=0||after.startsWith("%"))continue;
   boolean currency=before.matches("(?s).*(?:₹|\\bRS\\.?|\\bINR)\\s*[:=]?$")||token.endsWith("/-"),decimal=token.contains(".");
   boolean corrected=token.matches(".*[OIL].*");
   token=token.replaceAll("[,.]+$","");
   if(currency&&token.matches("(?:[0-9OIL]{1,3}(?:\\.[0-9OIL]{3})+|[0-9OIL]{1,3}(?:\\.[0-9OIL]{2})*\\.[0-9OIL]{3})\\.[0-9OIL]{2}")){int last=token.lastIndexOf('.');token=token.substring(0,last).replace('.',',')+token.substring(last);corrected=true;}
   if(!token.matches(".*[0-9].*")||token.startsWith("-")||token.startsWith("+")||token.contains("("))return Collections.emptyList();
   if(corrected&&(!decimal||!currency))return Collections.emptyList();
   token=token.replace("/-","").replace('O','0').replace('I','1').replace('L','1');
   if(!GROUPED.matcher(token).matches())return Collections.emptyList();
   if(!decimal&&!currency&&token.replace(",","").length()>=7)continue;
   try{long amount=ExpenseAmounts.parse(token.replace(",",""));values.add(new NumberValue(amount,currency,decimal,corrected));}catch(IllegalArgumentException|ArithmeticException invalid){return Collections.emptyList();}
  }
  return values;
 }
 public static Detection detect(String text){return detect(text,false);}
 public static Detection detect(String text,boolean debug){List<Row> rows=new ArrayList<>();if(text!=null){int index=0;for(String line:text.split("\\R"))rows.add(new Row(normalize(line.trim()),index++*30,20));}return evaluate(rows,debug);}
 public static Detection detect(List<Line> lines,boolean debug){Detection result=evaluate(rows(lines),debug);if(!debug)return result;StringBuilder geometry=new StringBuilder();for(Line line:lines)geometry.append("WORD [").append(line.left).append(',').append(line.top).append(',').append(line.right).append(',').append(line.bottom).append("] slope=").append(line.slope).append(' ').append(line.text).append('\n');return new Detection(result.status,result.amountPaise,result.reliability,result.supportingLabel,geometry.append(result.debugReport).toString());}
 /** Correct skew before joining aligned columns; reject vertical branding and deduplicate overlapping tiles. */
 private static List<Row> rows(List<Line> input){
  List<Line> lines=new ArrayList<>();List<Float> slopes=new ArrayList<>();
  for(Line line:input)if(Math.abs(line.slope)<=0.35f&&line.right>line.left&&line.bottom>line.top&&line.height()<(line.right-line.left)*2){slopes.add(line.slope);}
  Collections.sort(slopes);float slope=slopes.isEmpty()?0:slopes.get(slopes.size()/2);
  List<Line> complete=new ArrayList<>();
  for(Line line:input){boolean fragment=false;String key=observationKey(line.text);for(Line other:input){String full=observationKey(other.text);if(full.length()<=key.length()||!full.contains(key))continue;float overlap=Math.max(0,Math.min(line.right,other.right)-Math.max(line.left,other.left))*Math.max(0,Math.min(line.bottom,other.bottom)-Math.max(line.top,other.top));float area=(line.right-line.left)*line.height();if(area>0&&overlap/area>.65f){fragment=true;break;}}if(!fragment)complete.add(line);}
  for(Line line:complete){if(line.text.trim().isEmpty()||line.height()>(line.right-line.left)*2)continue;boolean duplicate=false;for(Line old:lines)if(observationKey(old.text).equals(observationKey(line.text))&&Math.abs(old.x()-line.x())<Math.max(old.height(),line.height())&&Math.abs(old.y()-line.y())<Math.max(old.height(),line.height())*.6f){duplicate=true;break;}if(!duplicate)lines.add(line);}
  lines.sort(Comparator.comparingDouble(l->l.y()-slope*l.x()));List<List<Line>> groups=new ArrayList<>();
  for(Line line:lines){List<Line> target=null;float distance=Float.MAX_VALUE;
   for(List<Line> group:groups){Line anchor=group.get(0);float dy=Math.abs((line.y()-slope*line.x())-(anchor.y()-slope*anchor.x()));if(dy<=Math.min(line.height(),anchor.height())*.65f&&dy<distance){target=group;distance=dy;}}
   if(target==null){target=new ArrayList<>();groups.add(target);}target.add(line);
  }
  List<Row> rows=new ArrayList<>();for(List<Line> group:groups){group.sort(Comparator.comparingDouble(l->l.left));StringBuilder text=new StringBuilder();Line anchor=group.get(0);Line previous=null;for(Line line:group){boolean cents=previous!=null&&previous.text.matches("[0-9OIL,]+\\.")&&line.text.matches("[0-9OIL]{1,2}")&&line.left-previous.right<=Math.max(previous.height(),line.height())*.65f;if(text.length()>0&&!cents)text.append(' ');text.append(line.text);previous=line;}rows.add(new Row(normalize(text.toString()),anchor.y()-slope*anchor.x(),anchor.height()));}rows.sort(Comparator.comparingDouble(r->r.y));return rows;
 }
 private static Detection evaluate(List<Row> rows,boolean debug){
  List<Candidate> candidates=new ArrayList<>();StringBuilder report=new StringBuilder();Set<Integer> unresolvedRanks=new HashSet<>();
  for(int i=0;i<rows.size();i++){
   Row row=rows.get(i);String line=row.text;if(debug){report.append("ROW ").append(i).append(" ").append(line).append('\n');report.append("EXTRACTED ");for(NumberValue value:numbers(line))report.append(value.value).append(value.corrected?"(corrected) ":" ");report.append('\n');}
   Matcher label=LABEL.matcher(line);if(!label.find())continue;
   String name=label.group().replaceAll("\\s+"," ");if(EXCLUDED.matcher(line).find()){if(debug)report.append("REJECT excluded field\n");continue;}
   String tail=line.substring(label.end());List<NumberValue> values=numbers(tail);
   if(values.isEmpty()&&!TOKEN.matcher(tail).find()&&i+1<rows.size()){
    Row next=rows.get(i+1);if(next.y-row.y<=Math.max(row.height,next.height)*2.5f&&next.text.matches("(?:INR|RS\\.?|₹)?[ :=]*[0-9OIL][0-9OIL, .]*(?:/-)?"))values=numbers(next.text);
   }
   if(values.size()!=1){unresolvedRanks.add(rank(name));if(debug)report.append("REJECT ").append(name).append(values.size()>1?" multiple amounts":" missing/invalid amount").append('\n');continue;}
   candidates.add(new Candidate(values.get(0),name,i));
  }
  for(Candidate candidate:candidates){
   for(int i=0;i<rows.size();i++){String line=rows.get(i).text;if(i!=candidate.row&&PAYMENT.matcher(line).find()&&!line.contains("TENDERED")){List<NumberValue> paid=numbers(line);if(paid.size()==1&&!paid.get(0).corrected&&paid.get(0).value==candidate.amount){candidate.score+=25;candidate.corroborated=true;candidate.reasons.add("independent payment amount agrees");break;}}}
   partialEvidence:for(int paid=0;paid<rows.size();paid++)if(paid!=candidate.row&&PAYMENT.matcher(rows.get(paid).text).find()){
    List<NumberValue> payment=numbers(rows.get(paid).text);if(payment.size()!=1||payment.get(0).corrected)continue;
    for(Row due:rows)if(due.text.matches("^(?:BALANCE\\s+)?(?:DUE|OUTSTANDING|AMOUNT\\s+DUE)\\b.*")){List<NumberValue> remainder=numbers(due.text);if(remainder.size()!=1||remainder.get(0).corrected)continue;try{if(Math.addExact(payment.get(0).value,remainder.get(0).value)==candidate.amount){candidate.score+=30;candidate.corroborated=true;candidate.arithmeticAgreement=true;candidate.reasons.add("partial payment plus outstanding agrees exactly");break partialEvidence;}}catch(ArithmeticException ignored){/* Not valid supporting arithmetic. */}}
   }
   // A NET AMOUNT or SUBTOTAL can precede taxes: only promote a later payable matching exact components.
   for(int base=0;base<candidate.row;base++){
    String line=rows.get(base).text;if(!line.matches(".*\\b(?:NET\\s+AMOUNT|SUB\\s*TOTAL|SUBTOT)\\b.*"))continue;List<NumberValue> parts=numbers(line);if(parts.size()!=1||parts.get(0).corrected)continue;
    long sum=parts.get(0).value;boolean tax=false,valid=true;Set<String> seen=new HashSet<>();boolean detailedTaxes=false;for(int j=base+1;j<candidate.row;j++)if(rows.get(j).text.matches(".*\\b(?:CGST[A-Z]*|SGST[A-Z]*|IGST[A-Z]*|UTGST[A-Z]*)\\b.*"))detailedTaxes=true;
    try{for(int j=base+1;j<candidate.row;j++){String adjustment=rows.get(j).text;if(detailedTaxes&&adjustment.matches(".*\\bTOTAL\\s+TAX\\b.*"))continue;boolean discount=adjustment.matches(".*\\b(?:DISCOUNT|COUPON)\\b.*");if(!TAX.matcher(adjustment).find()&&!discount)continue;List<NumberValue> values=numbers(adjustment);if(values.size()!=1||values.get(0).corrected){valid=false;break;}String key=adjustment.replaceAll("\\s+"," ");if(!seen.add(key))continue;sum=discount?Math.subtractExact(sum,values.get(0).value):Math.addExact(sum,values.get(0).value);tax=true;}}
    catch(ArithmeticException overflow){valid=false;}
    if(valid&&tax&&sum==candidate.amount){candidate.score+=40;candidate.corroborated=true;candidate.arithmeticAgreement=true;candidate.reasons.add("preceding base plus tax/charges minus discounts agrees exactly");break;}
   }
  }
  for(Candidate candidate:candidates)if(candidate.rank<=80&&!candidate.corroborated){for(int j=candidate.row+1;j<rows.size();j++)if(TAX.matcher(rows.get(j).text).find()&&!numbers(rows.get(j).text).isEmpty()){candidate.score-=30;candidate.reasons.add("tax/charge rows follow this intermediate amount");break;}}
  for(Candidate candidate:candidates)if(candidate.label.matches("AMOUNT PAID|PAID AMOUNT"))for(Row row:rows)if(row.text.matches("^(?:BALANCE\\s+)?(?:DUE|OUTSTANDING|AMOUNT\\s+DUE)\\b.*")&&!numbers(row.text).isEmpty()){candidate.score=0;candidate.reasons.add("partial payment is not the full bill amount");break;}
  // Merge repeated observations of the same amount without rewarding repeated text or overlap.
  Map<Long,Candidate> byAmount=new HashMap<>();for(Candidate candidate:candidates){Candidate old=byAmount.get(candidate.amount);if(old==null||candidate.score>old.score||(candidate.score==old.score&&candidate.label.compareTo(old.label)<0))byAmount.put(candidate.amount,candidate);}
  List<Candidate> ordered=new ArrayList<>(byAmount.values());ordered.sort(Comparator.comparingInt((Candidate c)->c.score).reversed().thenComparingLong(c->c.amount));
  if(debug)for(Candidate c:candidates)report.append("CANDIDATE ").append(c.amount).append(" paise label=").append(c.label).append(" score=").append(c.score).append(" reasons=").append(c.reasons).append('\n');
  Candidate best=ordered.isEmpty()?null:ordered.get(0);boolean ambiguous=best!=null&&ordered.size()>1&&best.score-ordered.get(1).score<20;
  if(best!=null&&ordered.size()>1&&best.rank>=100&&((ordered.get(1).rank>=80&&!best.corroborated)||(ordered.get(1).rank>=100&&!best.arithmeticAgreement)))ambiguous=true;
  if(best!=null)for(int missing:unresolvedRanks)if(missing>=best.rank&&!best.corroborated)ambiguous=true;
  if(best==null||best.score<78||best.corrected&&!best.corroborated||ambiguous){Status status=best==null?Status.NOT_FOUND:Status.AMBIGUOUS;return new Detection(status,null,Reliability.UNCERTAIN,null,debug?report.append("RESULT manual review required\n").toString():null);}
  Reliability reliability=best.score>=100&&!best.corrected?Reliability.HIGH:Reliability.REVIEW;
  return new Detection(Status.SUGGESTED,best.amount,reliability,best.label,debug?report.append("RESULT ").append(reliability).append('\n').toString():null);
 }
}
