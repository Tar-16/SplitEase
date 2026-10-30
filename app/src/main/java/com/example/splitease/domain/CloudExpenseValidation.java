package com.example.splitease.domain;
import com.example.splitease.model.*;
import java.util.*;
/** Technical bounds keep exact equal-share checks within Firestore Rules evaluation budgets. */
public final class CloudExpenseValidation {
 public static final int MAX_PARTICIPANTS=8;
 public static final long MAX_PAISE=1_000_000_000_000L;
 private CloudExpenseValidation(){}
 public static Map<String,Long> validate(Expense expense){
  Rules.id(expense.id);Rules.id(expense.groupId);Rules.date(expense.expenseDate);
  if(expense.amountPaise<=0||expense.amountPaise>MAX_PAISE||expense.revision<0||expense.revision==Long.MAX_VALUE||expense.title==null||expense.title.trim().isEmpty()||expense.title.length()>200||expense.description==null||expense.description.length()>2000||!Arrays.asList("Food","Travel","Rent","Bills","Other").contains(expense.category)||expense.splits.isEmpty()||expense.splits.size()>MAX_PARTICIPANTS)throw new IllegalArgumentException();
  List<String> ids=new ArrayList<>();for(ExpenseSplit split:expense.splits){if(!expense.id.equals(split.expenseId))throw new IllegalArgumentException();ids.add(split.userId);}
  Map<String,Long> expected=Rules.equalSplitIdentities(expense.amountPaise,ids);
  long sum=0;for(ExpenseSplit split:expense.splits){if(split.sharePaise!=expected.get(split.userId))throw new IllegalArgumentException();sum=Math.addExact(sum,split.sharePaise);}if(sum!=expense.amountPaise)throw new IllegalArgumentException();return expected;
 }
}
