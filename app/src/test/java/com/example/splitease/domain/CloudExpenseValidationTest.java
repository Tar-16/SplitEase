package com.example.splitease.domain;
import com.example.splitease.model.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class CloudExpenseValidationTest {
 private Expense expense(long amount,List<ExpenseSplit> shares){return new Expense("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002","Food",amount,"opaque-A","opaque-A","Food",0,shares,0,"");}
 private ExpenseSplit split(String uid,long share){return new ExpenseSplit("00000000-0000-0000-0000-000000000001",uid,share);}
 @Test public void opaqueIdentitiesUseSameDeterministicExactAlgorithm(){assertEquals(Long.valueOf(34),Rules.equalSplitIdentities(100,Arrays.asList("c","a","b")).get("a"));assertEquals(Rules.equalSplitIdentities(100,Arrays.asList("c","a","b")),Rules.equalSplitIdentities(100,Arrays.asList("b","c","a")));assertEquals(2,CloudExpenseValidation.validate(expense(101,Arrays.asList(split("a",51),split("b",50)))).size());}
 @Test public void invalidOrForgedSharesAreRejected(){for(Expense bad:Arrays.asList(expense(0,Arrays.asList(split("a",0))),expense(-1,Arrays.asList(split("a",-1))),expense(Long.MAX_VALUE,Arrays.asList(split("a",Long.MAX_VALUE))),expense(101,Arrays.asList(split("a",50),split("b",51))),expense(100,Arrays.asList(split("a",50),split("a",50))),expense(100,Collections.emptyList())))assertThrows(IllegalArgumentException.class,()->CloudExpenseValidation.validate(bad));}
 @Test public void eightParticipantsAndMaximumAmountConservePaise(){List<ExpenseSplit> shares=new ArrayList<>();List<String> ids=Arrays.asList("a","b","c","d","e","f","g","h");for(Map.Entry<String,Long> entry:Rules.equalSplitIdentities(CloudExpenseValidation.MAX_PAISE,ids).entrySet())shares.add(split(entry.getKey(),entry.getValue()));assertEquals(8,CloudExpenseValidation.validate(expense(CloudExpenseValidation.MAX_PAISE,shares)).size());shares.add(split("i",0));assertThrows(IllegalArgumentException.class,()->CloudExpenseValidation.validate(expense(CloudExpenseValidation.MAX_PAISE,shares)));}
 @Test public void localUuidValidationAndLongArithmeticRemainIntact(){assertThrows(IllegalArgumentException.class,()->Rules.equalSplit(100,Arrays.asList("opaque")));long total=0;for(long share:Rules.equalSplitIdentities(Long.MAX_VALUE,Arrays.asList("a","b","c")).values())total=Math.addExact(total,share);assertEquals(Long.MAX_VALUE,total);}
}
