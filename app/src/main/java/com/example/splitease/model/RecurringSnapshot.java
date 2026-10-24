package com.example.splitease.model;
import java.util.*;
/** Authorized local recurring projection; never includes emails or credentials. */
public final class RecurringSnapshot {
 public final List<GroupDetails> groups; public final List<RecurringBill> bills;
 public final Map<String,List<RecurringBillCycle>> cycles; public final Map<String,String> names;
 public RecurringSnapshot(List<GroupDetails> groups,List<RecurringBill> bills,Map<String,List<RecurringBillCycle>> cycles,Map<String,String> names) {
 this.groups=Collections.unmodifiableList(new ArrayList<>(groups));this.bills=Collections.unmodifiableList(new ArrayList<>(bills));this.cycles=Collections.unmodifiableMap(new HashMap<>(cycles));this.names=Collections.unmodifiableMap(new HashMap<>(names)); }
}
